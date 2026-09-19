package be.cookit.pos.android.data.fiscal

import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Small deterministic JSON encoder for local fiscal evidence.
 * Object keys are sorted lexicographically; array order is preserved.
 */
object FiscalCanonicalJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> finiteDecimal(value.toDouble())
        is Double -> finiteDecimal(value)
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is Map<*, *> -> value.entries
            .map { (key, item) -> (key?.toString() ?: error("Fiscal JSON key cannot be null")) to item }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, item) ->
                "${JSONObject.quote(key)}:${encode(item)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        else -> error("Unsupported canonical fiscal JSON type: ${value::class.java.name}")
    }

    fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun finiteDecimal(value: Double): String {
        require(value.isFinite()) { "Non-finite number is not valid fiscal JSON" }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
