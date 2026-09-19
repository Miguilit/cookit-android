package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.data.CookitApiException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FiscalCloudQueueResult(
    val publicId: String?,
    val status: String?,
    val snapshotHash: String?
)

/**
 * CookitFiscal cloud API used by the Android POS runtime.
 *
 * This client is deliberately separate from the application-integration client because
 * CookitFiscal lives under /api/v1/fiscal. User-authenticated queue/readiness calls use
 * the existing Sanctum bearer token; Fiscal Agent device-auth calls are handled by the
 * provider/agent layer in A14.2C.
 */
class FiscalCloudClient {
    private val origin = BuildConfig.COOKIT_API_BASE_URL
        .substringBefore("/api/application-integration")
        .trimEnd('/')

    suspend fun queueOrder(
        token: String,
        entity: FiscalOutboxEntity
    ): FiscalCloudQueueResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("idempotency_key", entity.idempotencyKey)
            .put("runtime_id", entity.runtimeId)
            .put("source_terminal_id", entity.terminalId)
            .put("source_channel", "android_pos")
            .put("local_event_id", entity.localEventId)
            .put("local_snapshot_hash", entity.snapshotHash)

        val json = request(
            path = "/api/v1/fiscal/orders/${entity.orderId}/queue",
            method = "POST",
            token = token,
            body = body,
            idempotencyKey = entity.idempotencyKey
        )

        val transaction = firstObject(json, "transaction", "data", "fiscal_transaction") ?: json
        FiscalCloudQueueResult(
            publicId = transaction.optText("public_id", "uuid", "id"),
            status = transaction.optText("status"),
            snapshotHash = transaction.optText("snapshot_hash")
        )
    }

    suspend fun readiness(token: String, branchId: Long): JSONObject = withContext(Dispatchers.IO) {
        request(
            path = "/api/v1/fiscal/readiness?branch_id=$branchId",
            method = "GET",
            token = token
        )
    }

    private fun request(
        path: String,
        method: String,
        token: String,
        body: JSONObject? = null,
        idempotencyKey: String? = null
    ): JSONObject {
        val connection = (URI.create(origin + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Authorization", "Bearer $token")
            idempotencyKey?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Idempotency-Key", it) }
            if (body != null) doOutput = true
        }

        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                throw CookitApiException(code, extractMessage(text, code), text)
            }
            if (text.isBlank()) return JSONObject()
            return if (text.trimStart().startsWith("[")) {
                JSONObject().put("data", JSONArray(text))
            } else {
                JSONObject(text)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractMessage(text: String, code: Int): String = runCatching {
        val json = JSONObject(text)
        val errors = json.optJSONObject("errors")
        if (errors != null) {
            val keys = errors.keys()
            while (keys.hasNext()) {
                val value = errors.opt(keys.next())
                when (value) {
                    is JSONArray -> if (value.length() > 0) return@runCatching value.optString(0)
                    is String -> if (value.isNotBlank()) return@runCatching value
                }
            }
        }
        json.optText("message", "error") ?: "Erreur fiscale HTTP $code"
    }.getOrDefault("Erreur fiscale HTTP $code")

    private fun firstObject(json: JSONObject, vararg keys: String): JSONObject? {
        keys.forEach { key -> json.optJSONObject(key)?.let { return it } }
        return null
    }
}

private fun JSONObject.optText(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = opt(key)?.toString()?.trim().orEmpty()
        if (value.isNotBlank() && value != "null") return value
    }
    return null
}
