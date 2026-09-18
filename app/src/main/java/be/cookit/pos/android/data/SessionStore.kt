package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.AppLanguage

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_pos_session", Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }
    fun email(): String? = prefs.getString("email", null)?.takeIf { it.isNotBlank() }
    fun language(): AppLanguage = AppLanguage.fromCode(prefs.getString("language", "fr"))

    fun save(token: String, email: String) {
        prefs.edit().putString("token", token).putString("email", email).apply()
    }

    fun saveLanguage(language: AppLanguage) {
        prefs.edit().putString("language", language.code).apply()
    }

    fun clearSession() {
        prefs.edit().remove("token").remove("email").apply()
    }
}
