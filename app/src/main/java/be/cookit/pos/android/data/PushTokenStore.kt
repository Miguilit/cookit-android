package be.cookit.pos.android.data

import android.content.Context

class PushTokenStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "cookit_pos_push",
            Context.MODE_PRIVATE
        )

    fun saveToken(token: String) {
        prefs.edit()
            .putString("fcm_token", token.trim())
            .apply()
    }

    fun token(): String? =
        prefs.getString("fcm_token", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun saveRegistrationContext(
        branchId: Long,
        deviceId: String
    ) {
        prefs.edit()
            .putLong("branch_id", branchId)
            .putString("device_id", deviceId.trim())
            .apply()
    }

    fun branchId(): Long? =
        prefs.getLong("branch_id", 0L)
            .takeIf { it > 0L }

    fun deviceId(): String? =
        prefs.getString("device_id", null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
