package be.cookit.pos.android.data

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

class NativeMutationOperationStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "cookit_native_mutation_operations",
        Context.MODE_PRIVATE
    )

    fun resolve(operation: String, orderId: Long, intentKey: String): String {
        val key = key(operation, orderId, intentKey)
        val existing = preferences.getString(key, null)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing

        val created = UUID.randomUUID().toString()
        check(preferences.edit().putString(key, created).commit()) {
            "Unable to persist native mutation operation id"
        }
        return created
    }

    fun complete(operation: String, orderId: Long, intentKey: String, clientOperationId: String) {
        val key = key(operation, orderId, intentKey)
        val existing = preferences.getString(key, null)?.trim().orEmpty()
        if (existing == clientOperationId) {
            check(preferences.edit().remove(key).commit()) {
                "Unable to clear native mutation operation id"
            }
        }
    }

    fun resolveOrderDraft(intentKey: String): String = resolve(
        operation = "order_prepare",
        orderId = 0L,
        intentKey = intentKey
    )

    fun completeOrderDraft(intentKey: String, clientOrderUuid: String) {
        complete(
            operation = "order_prepare",
            orderId = 0L,
            intentKey = intentKey,
            clientOperationId = clientOrderUuid
        )
    }

    private fun key(operation: String, orderId: Long, intentKey: String): String {
        val safeOperation = operation.trim().lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(intentKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(24)
        return "op.$safeOperation.$orderId.$digest"
    }
}
