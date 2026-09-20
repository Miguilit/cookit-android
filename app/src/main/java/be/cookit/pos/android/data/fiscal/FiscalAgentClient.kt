package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
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
 * Credentials are intentionally supplied by the caller and are not stored by this class. Android
 * stores them separately in FiscalAgentCredentialStore using Android Keystore-backed AES/GCM.
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

    suspend fun nextJob(
        credentials: FiscalAgentCredentials,
        identity: FiscalRuntimeIdentity
    ): FiscalAgentJob? = withContext(Dispatchers.IO) {
        require(credentials.configured) { "Fiscal Agent credentials not configured" }
        val runtime = URLEncoder.encode(identity.runtimeId, StandardCharsets.UTF_8.name())
        val path = "/api/v1/fiscal/agent/jobs/next?runtime_id=$runtime&runtime_type=android_pos"
        val connection = open(path, "GET", credentials)
        try {
            val code = connection.responseCode
            if (code == 204) return@withContext null
            val text = readResponse(connection, code)
            if (code !in 200..299) throw FiscalAgentException("Cookit Fiscal Agent HTTP $code", text)
            if (text.isBlank()) return@withContext null

            val envelope = JSONObject(text)
            if (!envelope.optBoolean("success", true)) {
                throw FiscalAgentException("Cookit Fiscal Agent returned success=false", text)
            }
            if (envelope.isNull("data")) return@withContext null
            val data = envelope.optJSONObject("data")
                ?: throw FiscalAgentException("Cookit Fiscal Agent returned malformed job data", text)
            FiscalAgentJob.fromJson(data)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun submitted(
        credentials: FiscalAgentCredentials,
        transactionId: Long,
        identity: FiscalRuntimeIdentity
    ): JSONObject = request(
        "/api/v1/fiscal/agent/jobs/$transactionId/submitted",
        credentials,
        runtimePayload(identity)
    )

    suspend fun acknowledge(
        credentials: FiscalAgentCredentials,
        transactionId: Long,
        identity: FiscalRuntimeIdentity,
        success: Boolean,
        error: String? = null,
        receipt: JSONObject? = null
    ): JSONObject {
        val payload = runtimePayload(identity)
            .put("success", success)
        error?.takeIf { it.isNotBlank() }?.let { payload.put("error", it) }
        receipt?.let { payload.put("receipt", it) }
        return request(
            "/api/v1/fiscal/agent/jobs/$transactionId/acknowledge",
            credentials,
            payload
        )
    }

    fun defaultHandshakePayload(
        identity: FiscalRuntimeIdentity,
        settings: FiscalFdmSettings
    ): JSONObject = runtimePayload(identity)
        .put("source_terminal_id", identity.terminalId)
        .put("provider", settings.provider)
        .put("agent_version", BuildConfig.VERSION_NAME)
        .put("protocol_version", "sce2_graphql")
        .put("capabilities", JSONArray(listOf("durable_outbox", "offline_recovery", "local_fdm_graphql", "cloud_job_runner_c3", "foreground_service_c4", "durable_provider_outcome_journal", "health_watchdog_c5_1")))

    fun defaultHeartbeatPayload(
        identity: FiscalRuntimeIdentity,
        settings: FiscalFdmSettings
    ): JSONObject = runtimePayload(identity)
        .put("source_terminal_id", identity.terminalId)
        .put("provider", settings.provider)
        .put("agent_version", BuildConfig.VERSION_NAME)
        .put("protocol_version", "sce2_graphql")

    private fun runtimePayload(identity: FiscalRuntimeIdentity): JSONObject = JSONObject()
        .put("runtime_id", identity.runtimeId)
        .put("runtime_type", "android_pos")

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
