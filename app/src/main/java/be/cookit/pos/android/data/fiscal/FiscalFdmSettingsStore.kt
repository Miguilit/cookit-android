package be.cookit.pos.android.data.fiscal

import android.content.Context

data class FiscalFdmSettings(
    val provider: String = PROVIDER_CHECKBOX,
    val host: String = "",
    val port: Int = 443,
    val path: String = "/graphql",
    val useTls: Boolean = true
) {
    val configured: Boolean get() = host.isNotBlank() && port in 1..65535
    val isMock: Boolean get() = provider == PROVIDER_MOCK
    val isModule2: Boolean get() = provider == PROVIDER_MODULE2

    val endpoint: String?
        get() = if (!configured) null else buildString {
            append(if (useTls) "https://" else "http://")
            append(host.trim())
            append(':')
            append(port)
            append(if (path.startsWith('/')) path else "/$path")
        }

    companion object {
        const val PROVIDER_CHECKBOX = "checkbox_eutronix"
        const val PROVIDER_MODULE2 = "module2_pracsys"
        const val PROVIDER_MOCK = "cookit_mock_fdm_a14_4"
    }
}

/**
 * Non-secret FDM network configuration.
 *
 * Provider authentication secrets/certificates are deliberately NOT persisted here. The certified
 * installer/provider layer must provision them through a dedicated secure mechanism once the exact
 * Checkbox/Eutronix security profile is known.
 *
 * A14.4 adds a debug-only Mock FDM provider. Its clear-text LAN transport is never accepted by the
 * production Checkbox adapter and is additionally gated by BuildConfig.ENABLE_MOCK_FDM at runtime.
 */
class FiscalFdmSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_fdm_runtime", Context.MODE_PRIVATE)

    fun load(): FiscalFdmSettings = FiscalFdmSettings(
        provider = prefs.getString(KEY_PROVIDER, FiscalFdmSettings.PROVIDER_CHECKBOX)
            ?: FiscalFdmSettings.PROVIDER_CHECKBOX,
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        port = prefs.getInt(KEY_PORT, 443).coerceIn(1, 65535),
        path = prefs.getString(KEY_PATH, "/graphql").orEmpty().ifBlank { "/graphql" },
        useTls = prefs.getBoolean(KEY_TLS, true)
    )

    fun save(settings: FiscalFdmSettings) {
        prefs.edit()
            .putString(KEY_PROVIDER, settings.provider)
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port.coerceIn(1, 65535))
            .putString(KEY_PATH, settings.path.ifBlank { "/graphql" })
            .putBoolean(KEY_TLS, settings.useTls)
            .apply()
    }

    companion object {
        private const val KEY_PROVIDER = "provider"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PATH = "path"
        private const val KEY_TLS = "tls"
    }
}
