package be.cookit.pos.android.data

import android.content.Context

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_pos_session", Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }
    fun email(): String? = prefs.getString("email", null)?.takeIf { it.isNotBlank() }

    fun save(token: String, email: String) {
        prefs.edit().putString("token", token).putString("email", email).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
