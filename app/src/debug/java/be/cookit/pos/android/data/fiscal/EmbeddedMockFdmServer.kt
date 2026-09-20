package be.cookit.pos.android.data.fiscal

import android.content.Context
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * A14.4.1 debug-only in-process Mock FDM.
 *
 * It binds strictly to Android loopback (127.0.0.1), never to Wi-Fi/LAN, and exists only in the
 * debug source set. It implements Cookit's test GraphQL contract, not the Checkbox/Eutronix schema.
 */
class EmbeddedMockFdmServer(context: Context) {
    private data class Receipt(
        val snapshotHash: String,
        val receiptNumber: String,
        val signature: String,
        val verificationCode: String,
        val providerReference: String
    )

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class RememberResult(
        val receipt: Receipt? = null,
        val duplicate: Boolean = false,
        val error: String? = null
    )

    private val receiptLock = Any()
    private val receiptPrefs = context.applicationContext.getSharedPreferences(
        "cookit_embedded_mock_fdm_receipts",
        Context.MODE_PRIVATE
    )

    // C4: the debug Mock is process-global. The POS ViewModel and foreground Fiscal Agent service
    // may each instantiate this wrapper, but there must still be only one loopback server bound to
    // 127.0.0.1:8787. Shared process state also lets the service recover after the UI is destroyed.
    companion object {
        private val running = AtomicBoolean(false)
        @Volatile private var serverSocket: ServerSocket? = null
        @Volatile private var acceptThread: Thread? = null
        @Volatile private var lastError: String? = null
    }

    fun start(): EmbeddedMockFdmStatus {
        if (running.get()) return status()
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(EmbeddedMockFdmContract.HOST), EmbeddedMockFdmContract.PORT))
            }
            serverSocket = socket
            running.set(true)
            lastError = null
            acceptThread = Thread({ acceptLoop(socket) }, "cookit-mock-fdm-accept").apply {
                isDaemon = true
                start()
            }
            status()
        } catch (error: Throwable) {
            running.set(false)
            lastError = error.message ?: error::class.java.simpleName
            status()
        }
    }

    fun stop(): EmbeddedMockFdmStatus {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        return status()
    }

    fun status(): EmbeddedMockFdmStatus = EmbeddedMockFdmStatus(
        available = true,
        running = running.get(),
        lastError = lastError
    )

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                Thread({ handleClient(client) }, "cookit-mock-fdm-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Throwable) {
                if (running.get()) lastError = error.message ?: error::class.java.simpleName
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 8_000
            val request = readRequest(client) ?: return
            when {
                request.method == "GET" && request.path == "/health" -> writeJson(
                    client,
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("service", "cookit-embedded-mock-fdm")
                        .put("version", "a14.4.1")
                )
                request.method == "POST" && request.path == EmbeddedMockFdmContract.PATH -> {
                    handleGraphql(client, request.headers, request.body)
                }
                else -> writeJson(client, 404, JSONObject().put("error", "not_found"))
            }
        }
    }

    private fun readRequest(client: Socket): HttpRequest? {
        val input = BufferedInputStream(client.getInputStream())
        val headerBuffer = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (headerBuffer.size() < 65_536) {
            val value = input.read()
            if (value < 0) return null
            headerBuffer.write(value)
            matched = if (value.toByte() == terminator[matched]) matched + 1 else if (value.toByte() == terminator[0]) 1 else 0
            if (matched == terminator.size) break
        }
        if (matched != terminator.size) return null

        val headerText = headerBuffer.toByteArray().toString(StandardCharsets.ISO_8859_1)
        val lines = headerText.substringBefore("\r\n\r\n").split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        val method = requestLine.getOrNull(0).orEmpty().uppercase(Locale.ROOT)
        val path = requestLine.getOrNull(1).orEmpty().substringBefore('?')
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2_000_000) ?: 0
        val bodyBytes = when {
            contentLength > 0 -> {
                val bytes = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val count = input.read(bytes, offset, contentLength - offset)
                    if (count <= 0) break
                    offset += count
                }
                if (offset == bytes.size) bytes else bytes.copyOf(offset)
            }
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunkedBody(input)
            else -> ByteArray(0)
        }
        val body = String(bodyBytes, StandardCharsets.UTF_8)
        return HttpRequest(method, path, headers, body)
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (output.size() <= 2_000_000) {
            val sizeLine = readAsciiLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (output.size() + size > 2_000_000) error("Mock request body too large")
            val chunk = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(chunk, offset, size - offset)
                if (count <= 0) error("Unexpected EOF in chunked Mock request")
                offset += count
            }
            output.write(chunk)
            readAsciiLine(input) // consumes CRLF following the chunk
        }
        return output.toByteArray()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        var previous = -1
        while (buffer.size() < 8_192) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString(StandardCharsets.ISO_8859_1.name())
            if (previous == 13 && value == 10) {
                val bytes = buffer.toByteArray()
                val length = (bytes.size - 1).coerceAtLeast(0)
                return String(bytes, 0, length, StandardCharsets.ISO_8859_1)
            }
            buffer.write(value)
            previous = value
        }
        error("Mock HTTP line too long")
    }

    private fun handleGraphql(client: Socket, headers: Map<String, String>, rawBody: String) {
        val scenario = headers["x-cookit-mock-scenario"]?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { "success" }
        val envelope = runCatching { JSONObject(rawBody) }.getOrElse {
            writeJson(client, 400, JSONObject().put("errors", org.json.JSONArray().put(JSONObject().put("message", "Invalid request JSON"))))
            return
        }

        val operationName = envelope.optString("operationName")
        val query = envelope.optString("query")
        if (operationName == "CookitConnectivityProbe" || query.contains("__typename")) {
            writeJson(client, 200, JSONObject().put("data", JSONObject().put("__typename", "Query")))
            return
        }

        when (scenario) {
            "auth_required" -> {
                writeJson(client, 401, JSONObject().put("error", "mock_auth_required"))
                return
            }
            "http_500" -> {
                writeJson(client, 500, JSONObject().put("error", "mock_http_500"))
                return
            }
            "graphql_error" -> {
                writeJson(
                    client,
                    200,
                    JSONObject().put(
                        "errors",
                        org.json.JSONArray().put(JSONObject().put("message", "Mock GraphQL rejection"))
                    )
                )
                return
            }
            "malformed" -> {
                writeRaw(client, 200, "application/json; charset=utf-8", "{\"data\":")
                return
            }
            "slow" -> Thread.sleep(3_000)
            "timeout" -> {
                Thread.sleep(25_000)
                return
            }
        }

        if (operationName != "CookitMockSignSale" && !query.contains("signSale")) {
            writeJson(
                client,
                200,
                JSONObject().put("errors", org.json.JSONArray().put(JSONObject().put("message", "UNKNOWN_MOCK_OPERATION")))
            )
            return
        }

        val input = envelope.optJSONObject("variables")?.optJSONObject("input")
        if (input == null) {
            writeJson(
                client,
                200,
                JSONObject().put("errors", org.json.JSONArray().put(JSONObject().put("message", "MockSaleInput missing")))
            )
            return
        }

        val idempotencyKey = input.optString("idempotencyKey")
        val snapshotJson = input.optString("snapshotJson")
        val snapshotHash = input.optString("snapshotHash")
        if (idempotencyKey.isBlank() || snapshotJson.isBlank() || snapshotHash.isBlank()) {
            writeJson(
                client,
                200,
                JSONObject().put("errors", org.json.JSONArray().put(JSONObject().put("message", "Required mock fiscal fields missing")))
            )
            return
        }
        val calculated = sha256Hex(snapshotJson)
        if (!calculated.equals(snapshotHash, ignoreCase = true)) {
            writeJson(
                client,
                200,
                JSONObject().put("errors", org.json.JSONArray().put(JSONObject().put("message", "Snapshot SHA-256 mismatch")))
            )
            return
        }

        val remembered = rememberReceipt(
            idempotencyKey = idempotencyKey,
            snapshotHash = snapshotHash.lowercase(Locale.ROOT),
            localEventId = input.optString("localEventId")
        )
        if (remembered.error != null || remembered.receipt == null) {
            writeJson(
                client,
                200,
                JSONObject().put("errors", org.json.JSONArray().put(JSONObject().put("message", remembered.error ?: "Mock ledger error")))
            )
            return
        }
        val receipt = remembered.receipt
        val duplicate = remembered.duplicate

        if (scenario == "lost_response" || (scenario == "lost_response_once" && !duplicate)) {
            // The mock has durably accepted the idempotency key, but intentionally drops the HTTP reply.
            // lost_response always drops; lost_response_once drops only the first acceptance so the
            // lease retry can recover the same receipt with duplicate=true.
            return
        }

        val sale = JSONObject()
            .put("success", true)
            .put("receiptNumber", receipt.receiptNumber)
            .put("signature", receipt.signature)
            .put("verificationCode", receipt.verificationCode)
            .put("providerReference", receipt.providerReference)
            .put("duplicate", duplicate)
        writeJson(client, 200, JSONObject().put("data", JSONObject().put("signSale", sale)))
    }

    private fun rememberReceipt(
        idempotencyKey: String,
        snapshotHash: String,
        localEventId: String
    ): RememberResult = synchronized(receiptLock) {
        val storageKey = "receipt_${sha256Hex(idempotencyKey)}"
        val existingRaw = receiptPrefs.getString(storageKey, null)
        if (!existingRaw.isNullOrBlank()) {
            val stored = runCatching { JSONObject(existingRaw) }.getOrNull()
            if (stored != null) {
                val storedHash = stored.optString("snapshotHash")
                if (!storedHash.equals(snapshotHash, ignoreCase = true)) {
                    return@synchronized RememberResult(
                        error = "IDEMPOTENCY_CONFLICT: same key reused with another snapshot hash"
                    )
                }
                return@synchronized RememberResult(
                    receipt = Receipt(
                        snapshotHash = storedHash,
                        receiptNumber = stored.optString("receiptNumber"),
                        signature = stored.optString("signature"),
                        verificationCode = stored.optString("verificationCode"),
                        providerReference = stored.optString("providerReference")
                    ),
                    duplicate = true
                )
            }
        }

        val receipt = receiptFor(idempotencyKey, snapshotHash, localEventId)
        val serialized = JSONObject()
            .put("snapshotHash", receipt.snapshotHash)
            .put("receiptNumber", receipt.receiptNumber)
            .put("signature", receipt.signature)
            .put("verificationCode", receipt.verificationCode)
            .put("providerReference", receipt.providerReference)
            .toString()
        if (!receiptPrefs.edit().putString(storageKey, serialized).commit()) {
            return@synchronized RememberResult(error = "Mock FDM could not persist its idempotency ledger")
        }
        RememberResult(receipt = receipt, duplicate = false)
    }

    private fun receiptFor(idempotencyKey: String, snapshotHash: String, localEventId: String): Receipt {
        val digest = sha256Hex("$idempotencyKey|$snapshotHash")
        return Receipt(
            snapshotHash = snapshotHash,
            receiptNumber = "CKT-MOCK-${digest.take(12).uppercase(Locale.ROOT)}",
            signature = "MOCKSIG-${digest.substring(12, 44).uppercase(Locale.ROOT)}",
            verificationCode = digest.takeLast(10).uppercase(Locale.ROOT),
            providerReference = "mock:${localEventId.ifBlank { digest.takeLast(16) }}"
        )
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun writeJson(client: Socket, code: Int, json: JSONObject) = writeRaw(
        client,
        code,
        "application/json; charset=utf-8",
        json.toString()
    )

    private fun writeRaw(client: Socket, code: Int, contentType: String, body: String) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Mock"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
        writer.write("HTTP/1.1 $code $reason\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("Cache-Control: no-store\r\n")
        writer.write("X-Cookit-Mock-FDM: a14.4.1-embedded\r\n")
        writer.write("\r\n")
        writer.flush()
        client.getOutputStream().write(bytes)
        client.getOutputStream().flush()
    }
}
