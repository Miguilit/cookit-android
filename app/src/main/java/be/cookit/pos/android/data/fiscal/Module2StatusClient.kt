package be.cookit.pos.android.data.fiscal

import android.content.Context
import be.cookit.pos.android.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Module2StatusResult(
    val endpoint: String = "",
    val connected: Boolean = false,
    val statusAvailable: Boolean = false,
    val httpStatus: Int? = null,
    val fdmId: String? = null,
    val fdmSwVersion: String? = null,
    val fdmDateTime: String? = null,
    val bufferCapacityUsed: Double? = null,
    val initialized: Boolean? = null,
    val informations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val schemaVariant: String? = null,
    val message: String? = null
) {
    fun normalized(): FiscalProviderStatus = FiscalProviderStatus(
        provider = FiscalFdmSettings.PROVIDER_MODULE2,
        transportConnected = connected,
        statusAvailable = statusAvailable,
        fdmId = fdmId,
        firmwareVersion = fdmSwVersion,
        fdmDateTime = fdmDateTime,
        bufferCapacityUsed = bufferCapacityUsed,
        initialized = initialized,
        informations = informations,
        warnings = warnings,
        errors = errors,
        httpStatus = httpStatus,
        latencyMs = latencyMs,
        schemaVariant = schemaVariant,
        message = message
    )
}

private data class Module2HttpResponse(
    val status: Int,
    val body: String,
    val latencyMs: Long
)

private data class Module2StatusQueryCandidate(
    val variant: String,
    val query: String
)

/**
 * A15.0B Module2 read-only status client.
 *
 * GKS 2.0 defines a standard status query. Historic public specification revisions contained a
 * fmdSwVersion typo while current revisions use fdmSwVersion. We probe both forms and normalize the
 * response so the rest of Cookit never depends on a manufacturer/schema spelling detail.
 */
class Module2StatusClient(private val context: Context) {
    suspend fun status(
        settings: FiscalFdmSettings,
        bearerToken: String
    ): Module2StatusResult = withContext(Dispatchers.IO) {
        require(settings.isModule2) { "Module2 provider is not selected" }
        require(settings.useTls) { "Module2 requires HTTPS/mTLS" }
        require(settings.configured) { "Module2 endpoint is not configured" }
        val token = bearerToken.trim().removePrefix("Bearer ").trim()
        require(token.isNotBlank()) { "Module2 Bearer token is not configured" }

        val endpoint = settings.endpoint ?: error("Module2 endpoint unavailable")
        var totalLatency = 0L
        val failures = mutableListOf<String>()

        try {
            val sslContext = createModule2SslContext()
            for (candidate in statusCandidates()) {
                val response = post(
                    endpoint = endpoint,
                    bearerToken = token,
                    sslContext = sslContext,
                    operationName = "CookitModule2Status",
                    query = candidate.query
                )
                totalLatency += response.latencyMs

                if (response.status !in 200..299) {
                    return@withContext Module2StatusResult(
                        endpoint = endpoint,
                        connected = false,
                        statusAvailable = false,
                        httpStatus = response.status,
                        latencyMs = totalLatency,
                        message = "Module2 HTTP ${response.status}"
                    )
                }

                val envelope = runCatching { JSONObject(response.body) }.getOrNull()
                val payload = envelope?.optJSONObject("data")?.optJSONObject("status")
                if (payload != null) {
                    val device = payload.optJSONObject("device")
                    return@withContext Module2StatusResult(
                        endpoint = endpoint,
                        connected = true,
                        statusAvailable = true,
                        httpStatus = response.status,
                        fdmId = device?.optNonBlank("fdmId"),
                        fdmSwVersion = device?.optNonBlank("fdmSwVersion"),
                        fdmDateTime = device?.optNonBlank("fdmDateTime"),
                        bufferCapacityUsed = device?.optNullableDouble("bufferCapacityUsed"),
                        initialized = payload.optNullableBoolean("initialized"),
                        informations = payload.messageList("informations"),
                        warnings = payload.messageList("warnings"),
                        errors = payload.messageList("errors"),
                        latencyMs = totalLatency,
                        schemaVariant = candidate.variant,
                        message = "Module2 status OK (${candidate.variant})"
                    )
                }

                failures += "${candidate.variant}: ${envelope?.firstGraphqlError() ?: "data.status missing"}"
            }

            // Transport-only fallback. This deliberately performs no fiscal mutation.
            val handshake = post(
                endpoint = endpoint,
                bearerToken = token,
                sslContext = sslContext,
                operationName = "CookitModule2Handshake",
                query = "query CookitModule2Handshake { __typename }"
            )
            totalLatency += handshake.latencyMs
            val handshakeEnvelope = runCatching { JSONObject(handshake.body) }.getOrNull()
            val handshakeOkay = handshake.status in 200..299 &&
                handshakeEnvelope?.optJSONObject("data")?.optString("__typename")?.isNotBlank() == true

            Module2StatusResult(
                endpoint = endpoint,
                connected = handshakeOkay,
                statusAvailable = false,
                httpStatus = handshake.status,
                latencyMs = totalLatency,
                schemaVariant = "TRANSPORT_ONLY",
                message = if (handshakeOkay) {
                    "Module2 transport OK; status schema unresolved (${failures.joinToString(" | ")})"
                } else {
                    handshakeEnvelope?.firstGraphqlError()
                        ?: "Module2 GraphQL handshake failed (${failures.joinToString(" | ")})"
                }
            )
        } catch (error: Throwable) {
            Module2StatusResult(
                endpoint = endpoint,
                connected = false,
                statusAvailable = false,
                latencyMs = totalLatency.takeIf { it > 0 },
                message = error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun statusCandidates(): List<Module2StatusQueryCandidate> = listOf(
        // First mirror the Module2 developer manual exactly: status without arguments.
        Module2StatusQueryCandidate(
            variant = "MODULE2_MANUAL",
            query = """
                query CookitModule2Status {
                  status {
                    device {
                      fdmId
                      fdmSwVersion
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        ),
        // Keep a language-qualified form for GKS schema revisions that expose the argument.
        Module2StatusQueryCandidate(
            variant = "GKS_2_LANGUAGE_EN",
            query = """
                query CookitModule2Status {
                  status(language: EN) {
                    device {
                      fdmId
                      fdmSwVersion
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        ),
        // Historic public revisions contained fmdSwVersion; alias it to our normalized name.
        Module2StatusQueryCandidate(
            variant = "GKS_2_LEGACY_FMD_SW",
            query = """
                query CookitModule2Status {
                  status {
                    device {
                      fdmId
                      fdmSwVersion: fmdSwVersion
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        ),
        Module2StatusQueryCandidate(
            variant = "GKS_2_MINIMAL",
            query = """
                query CookitModule2Status {
                  status {
                    device {
                      fdmId
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        )
    )

    private fun post(
        endpoint: String,
        bearerToken: String,
        sslContext: SSLContext,
        operationName: String,
        query: String
    ): Module2HttpResponse {
        val started = System.nanoTime()
        var connection: HttpsURLConnection? = null
        try {
            val conn = (URI.create(endpoint).toURL().openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                sslSocketFactory = sslContext.socketFactory
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("User-Agent", "CookitPOS-Android-Module2-A15.0B1")
            }
            connection = conn

            val body = JSONObject()
                .put("operationName", operationName)
                .put("query", query)
                .put("variables", JSONObject())

            conn.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(body.toString()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

            return Module2HttpResponse(
                status = status,
                body = text,
                latencyMs = (System.nanoTime() - started) / 1_000_000L
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun JSONObject.firstGraphqlError(): String? {
        val errors = optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        return errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            ?: errors.optString(0).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNonBlank(name: String): String? =
        optString(name).takeIf { has(name) && !isNull(name) && it.isNotBlank() }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getDouble(name) }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getBoolean(name) }.getOrNull()
    }

    private fun JSONObject.messageList(name: String): List<String> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                item.optString("message").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun createModule2SslContext(): SSLContext {
        // Module2 distributes the client identity with an empty-password PKCS#12 bundle.
        // Android providers are inconsistent when decrypting empty-password PKCS#12 files
        // (some devices throw IllegalArgumentException: password empty).  Load the same
        // published client certificate + unencrypted PKCS#8 key directly instead.
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val clientCertificate = context.resources.openRawResource(R.raw.module2_client_cert).use { stream ->
            certificateFactory.generateCertificate(stream)
        }
        val caCertificate = context.resources.openRawResource(R.raw.module2_ca).use { stream ->
            certificateFactory.generateCertificate(stream)
        }
        val privateKey = context.resources.openRawResource(R.raw.module2_client_key).use { stream ->
            val pem = stream.bufferedReader(StandardCharsets.US_ASCII).use { it.readText() }
            val base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
            val encoded = Base64.decode(base64, Base64.DEFAULT)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
        }

        // The password below protects only this in-memory KeyStore entry. It is not a
        // Module2 credential and is never transmitted or persisted.
        val inMemoryKeyPassword = "cookit-module2-mtls".toCharArray()
        val clientStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(
                "module2-client",
                privateKey,
                inMemoryKeyPassword,
                arrayOf(clientCertificate, caCertificate)
            )
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientStore, inMemoryKeyPassword)
        }.keyManagers

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("module2-root-ca", caCertificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }.trustManagers

        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
    }
}
