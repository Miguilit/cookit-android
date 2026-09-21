package be.cookit.pos.android.data.fiscal

import android.content.Context
import be.cookit.pos.android.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Module2StatusResult(
    val endpoint: String = "",
    val connected: Boolean = false,
    val httpStatus: Int? = null,
    val fdmId: String? = null,
    val fdmSwVersion: String? = null,
    val fdmDateTime: String? = null,
    val bufferCapacityUsed: Int? = null,
    val initialized: Boolean? = null,
    val warningCount: Int = 0,
    val errorCount: Int = 0,
    val latencyMs: Long? = null,
    val message: String? = null
)

private data class Module2HttpResponse(
    val status: Int,
    val body: String,
    val latencyMs: Long
)

/**
 * A15.0A read-only Module2 simulator/physical-FDM client.
 *
 * The primary check requests the documented status object. If a translated/manual schema label
 * differs, A15.0A falls back to a GraphQL __typename handshake. This still proves the complete
 * mTLS + Bearer + GraphQL transport without enabling any fiscal mutation.
 */
class Module2StatusClient(private val context: Context) {
    suspend fun status(
        settings: FiscalFdmSettings,
        bearerToken: String
    ): Module2StatusResult = withContext(Dispatchers.IO) {
        require(settings.isModule2) { "Module2 provider is not selected" }
        require(settings.useTls) { "Module2 A15.0A requires HTTPS/mTLS" }
        require(settings.configured) { "Module2 endpoint is not configured" }
        val token = bearerToken.trim().removePrefix("Bearer ").trim()
        require(token.isNotBlank()) { "Module2 Bearer token is not configured" }

        val endpoint = settings.endpoint ?: error("Module2 endpoint unavailable")
        val sslContext = createModule2SslContext()

        try {
            val statusResponse = post(
                endpoint = endpoint,
                bearerToken = token,
                sslContext = sslContext,
                operationName = "CookitModule2Status",
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
            )

            if (statusResponse.status !in 200..299) {
                return@withContext Module2StatusResult(
                    endpoint = endpoint,
                    connected = false,
                    httpStatus = statusResponse.status,
                    latencyMs = statusResponse.latencyMs,
                    message = "Module2 HTTP ${statusResponse.status}"
                )
            }

            val envelope = runCatching { JSONObject(statusResponse.body) }.getOrNull()
            val payload = envelope?.optJSONObject("data")?.optJSONObject("status")
            if (payload != null) {
                val device = payload.optJSONObject("device")
                return@withContext Module2StatusResult(
                    endpoint = endpoint,
                    connected = true,
                    httpStatus = statusResponse.status,
                    fdmId = device?.optString("fdmId")?.takeIf { it.isNotBlank() },
                    fdmSwVersion = device?.optString("fdmSwVersion")?.takeIf { it.isNotBlank() },
                    fdmDateTime = device?.optString("fdmDateTime")?.takeIf { it.isNotBlank() },
                    bufferCapacityUsed = device?.optInt("bufferCapacityUsed")?.takeIf { it >= 0 },
                    initialized = if (payload.has("initialized") && !payload.isNull("initialized")) payload.optBoolean("initialized") else null,
                    warningCount = payload.optJSONArray("warnings")?.length() ?: 0,
                    errorCount = payload.optJSONArray("errors")?.length() ?: 0,
                    latencyMs = statusResponse.latencyMs,
                    message = "Module2 mTLS + Bearer + GraphQL status OK"
                )
            }

            val statusGraphqlError = envelope?.firstGraphqlError()
                ?: if (statusResponse.body.isBlank()) "empty status response" else "data.status missing"

            // Schema-safe transport fallback: no fiscal operation and no introspection dependency.
            val handshake = post(
                endpoint = endpoint,
                bearerToken = token,
                sslContext = sslContext,
                operationName = "CookitModule2Handshake",
                query = "query CookitModule2Handshake { __typename }"
            )
            val handshakeEnvelope = runCatching { JSONObject(handshake.body) }.getOrNull()
            val handshakeOkay = handshake.status in 200..299 &&
                handshakeEnvelope?.optJSONObject("data")?.optString("__typename")?.isNotBlank() == true

            Module2StatusResult(
                endpoint = endpoint,
                connected = handshakeOkay,
                httpStatus = handshake.status,
                latencyMs = statusResponse.latencyMs + handshake.latencyMs,
                message = if (handshakeOkay) {
                    "Module2 mTLS + Bearer + GraphQL handshake OK; status query needs schema confirmation ($statusGraphqlError)"
                } else {
                    handshakeEnvelope?.firstGraphqlError()
                        ?: "Module2 GraphQL handshake failed after status query: $statusGraphqlError"
                }
            )
        } catch (error: Throwable) {
            Module2StatusResult(
                endpoint = endpoint,
                connected = false,
                message = error.message ?: error::class.java.simpleName
            )
        }
    }

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
                setRequestProperty("User-Agent", "CookitPOS-Android-Module2-A15.0A")
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

    private fun createModule2SslContext(): SSLContext {
        val clientStore = KeyStore.getInstance("PKCS12").apply {
            context.resources.openRawResource(R.raw.module2_client).use { stream -> load(stream, charArrayOf()) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientStore, charArrayOf())
        }.keyManagers

        val caCertificate = context.resources.openRawResource(R.raw.module2_ca).use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificate(stream)
        }
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
