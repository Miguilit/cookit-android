package be.cookit.pos.android.data.fiscal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FdmConnectivityProbeResult(
    val endpoint: String = "",
    val attempted: Boolean = false,
    val tlsConnected: Boolean = false,
    val mockCleartextConnected: Boolean = false,
    val httpStatus: Int? = null,
    val graphqlResponded: Boolean = false,
    val certificateSha256: String? = null,
    val latencyMs: Long? = null,
    val message: String? = null
) {
    val networkConnected: Boolean get() = tlsConnected || mockCleartextConnected
    val transportReady: Boolean get() = networkConnected && httpStatus != null
}

/** Read-only GraphQL connectivity probe; never sends a fiscal mutation. */
class FdmConnectivityProbe {
    suspend fun probe(settings: FiscalFdmSettings): FdmConnectivityProbeResult = withContext(Dispatchers.IO) {
        val endpoint = settings.endpoint.orEmpty()
        if (!settings.configured) {
            return@withContext FdmConnectivityProbeResult(endpoint = endpoint, message = "FDM host/port not configured")
        }
        if (!settings.useTls && !MockFdmDebugGuard.cleartextAllowed(settings)) {
            return@withContext FdmConnectivityProbeResult(
                endpoint = endpoint,
                attempted = false,
                message = "Clear-text transport blocked outside debug Mock FDM/private LAN"
            )
        }

        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            val conn = (URI.create(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7_500
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "CookitPOS-Android-FDM-Probe")
            }
            connection = conn

            val body = JSONObject()
                .put("query", "query CookitConnectivityProbe { __typename }")
                .put("operationName", "CookitConnectivityProbe")

            conn.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            val status = conn.responseCode
            val latency = (System.nanoTime() - started) / 1_000_000L
            val https = conn is HttpsURLConnection
            val certFingerprint = if (https) runCatching {
                val certificate = (conn as HttpsURLConnection).serverCertificates.firstOrNull() ?: return@runCatching null
                MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull() else null

            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()
            val json = runCatching { if (text.isBlank()) null else JSONObject(text) }.getOrNull()
            val graphql = json?.has("data") == true || json?.has("errors") == true

            FdmConnectivityProbeResult(
                endpoint = endpoint,
                attempted = true,
                tlsConnected = https,
                mockCleartextConnected = !https && MockFdmDebugGuard.cleartextAllowed(settings),
                httpStatus = status,
                graphqlResponded = graphql,
                certificateSha256 = certFingerprint,
                latencyMs = latency,
                message = when {
                    !https && settings.isMock && status in 200..299 && graphql -> "Debug Mock FDM reachable over local/private HTTP"
                    status in 200..299 && graphql -> "TLS + GraphQL endpoint reachable"
                    status in 200..299 -> "Transport reachable; response is not a GraphQL envelope"
                    status == 401 || status == 403 -> "Transport reachable; FDM authentication is required"
                    else -> "Transport reachable; HTTP $status"
                }
            )
        } catch (error: Throwable) {
            FdmConnectivityProbeResult(
                endpoint = endpoint,
                attempted = true,
                tlsConnected = false,
                mockCleartextConnected = false,
                latencyMs = (System.nanoTime() - started) / 1_000_000L,
                message = error.message ?: error::class.java.simpleName
            )
        } finally {
            connection?.disconnect()
        }
    }
}
