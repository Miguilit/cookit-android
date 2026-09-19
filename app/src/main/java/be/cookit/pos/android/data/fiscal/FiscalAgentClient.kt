package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FiscalAgentCredentials(
    val deviceId: String,
    val deviceToken: String
) {
    val configured: Boolean get() = deviceId.isNotBlank() && deviceToken.isNotBlank()
}

class FiscalAgentException(message: String, val responseBody: String = "") : Exception(message)

/**
 * Device-auth transport for the Cookit Fiscal Agent contract.
 *
 * Credentials are intentionally supplied by the caller and are not stored by this class. Android does
 * not auto-handshake until a backend-provisioned `fiscal_agent` device identity exists.
 */
class FiscalAgentClient {
    private val origin = BuildConfig.COOKIT_API_BASE_URL
        .substringBefore("/api/application-integration")
        .trimEnd('/')

    suspend fun handshake(
        credentials: FiscalAgentCredentials,
        payload: JSONObject
    ): JSONObject = request("/api/v1/fiscal/agent/handshake", credentials, payload)

    suspend fun heartbeat(
        credentials: FiscalAgentCredentials,
        payload: JSONObject
    ): JSONObject = request("/api/v1/fiscal/agent/heartbeat", credentials, payload)

    suspend fun nextJob(credentials: FiscalAgentCredentials): JSONObject? = withContext(Dispatchers.IO) {
        require(credentials.configured) { "Fiscal Agent credentials not configured" }
        val connection = open("/api/v1/fiscal/agent/jobs/next", "GET", credentials)
        try {
            val code = connection.responseCode
            if (code == 204) return@withContext null
            val text = readResponse(connection, code)
            if (code !in 200..299) throw FiscalAgentException("Cookit Fiscal Agent HTTP $code", text)
            if (text.isBlank()) null else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun submitted(
        credentials: FiscalAgentCredentials,
        publicId: String,
        payload: JSONObject = JSONObject()
    ): JSONObject = request("/api/v1/fiscal/agent/jobs/$publicId/submitted", credentials, payload)

    suspend fun acknowledge(
        credentials: FiscalAgentCredentials,
        publicId: String,
        payload: JSONObject
    ): JSONObject = request("/api/v1/fiscal/agent/jobs/$publicId/acknowledge", credentials, payload)

    fun defaultHandshakePayload(
        identity: be.cookit.pos.android.domain.FiscalRuntimeIdentity,
        settings: FiscalFdmSettings
    ): JSONObject = JSONObject()
        .put("runtime_id", identity.runtimeId)
        .put("source_terminal_id", identity.terminalId)
        .put("runtime_type", "android_pos")
        .put("provider", settings.provider)
        .put("agent_version", BuildConfig.VERSION_NAME)
        .put("protocol_version", "sce2_graphql")
        .put("capabilities", JSONArray(listOf("durable_outbox", "offline_recovery", "local_fdm_graphql")))

    private suspend fun request(
        path: String,
        credentials: FiscalAgentCredentials,
        body: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        require(credentials.configured) { "Fiscal Agent credentials not configured" }
        val connection = open(path, "POST", credentials).apply { doOutput = true }
        try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val text = readResponse(connection, code)
            if (code !in 200..299) throw FiscalAgentException("Cookit Fiscal Agent HTTP $code", text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        path: String,
        method: String,
        credentials: FiscalAgentCredentials
    ): HttpURLConnection = (URI.create(origin + path).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("X-Cookit-Device", credentials.deviceId)
        setRequestProperty("X-Cookit-Device-Token", credentials.deviceToken)
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.let {
            BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
        }.orEmpty()
    }
}
