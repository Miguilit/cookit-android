package be.cookit.pos.android.data.fiscal

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FdmGraphqlOperation(
    val query: String,
    val operationName: String? = null,
    val variables: JSONObject = JSONObject()
)

class FdmGraphqlException(message: String, val responseBody: String = "") : Exception(message)

/**
 * Network-only POS -> FDM GraphQL transport.
 *
 * Production providers remain HTTPS-only. A14.4 permits clear-text only for the debug Mock FDM and
 * only when its host resolves to loopback/RFC1918/link-local space; release builds keep the exception
 * disabled through BuildConfig.ENABLE_MOCK_FDM=false.
 */
class FdmGraphqlClient {
    suspend fun execute(
        settings: FiscalFdmSettings,
        operation: FdmGraphqlOperation,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        require(settings.configured) { "FDM host/port not configured" }
        if (!settings.useTls) MockFdmDebugGuard.requireAllowed(settings)

        val endpoint = settings.endpoint ?: error("FDM endpoint unavailable")
        val body = JSONObject()
            .put("query", operation.query)
            .put("variables", operation.variables)
        operation.operationName?.let { body.put("operationName", it) }

        val connection = (URI.create(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "CookitPOS-Android-FDM")
            extraHeaders.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) setRequestProperty(key, value)
            }
        }

        try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                throw FdmGraphqlException("FDM GraphQL HTTP $code", text)
            }

            val json = try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (error: Throwable) {
                throw FdmGraphqlException("FDM returned malformed JSON", text)
            }
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: errors.optString(0).takeIf { it.isNotBlank() }
                    ?: "FDM GraphQL error"
                throw FdmGraphqlException(first, text)
            }
            json
        } finally {
            connection.disconnect()
        }
    }
}
