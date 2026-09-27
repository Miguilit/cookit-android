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
        internal const val LEGACY_PROVIDER_MOCK = "cookit_mock_fdm_a14_4"
    }
}

/**
 * Non-secret FDM network configuration.
 *
 * The embedded Mock FDM runtime was retired in 0.15.0.42. A persisted legacy
 * mock provider value is migrated once to the Module2 certification transport.
 * Provider authentication secrets/certificates remain in their dedicated
 * secure stores.
 */
class FiscalFdmSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_fdm_runtime", Context.MODE_PRIVATE)

    fun load(): FiscalFdmSettings {
        val storedProvider = prefs.getString(
            KEY_PROVIDER,
            FiscalFdmSettings.PROVIDER_CHECKBOX
        ) ?: FiscalFdmSettings.PROVIDER_CHECKBOX

        if (storedProvider == FiscalFdmSettings.LEGACY_PROVIDER_MOCK) {
            val migrated = FiscalFdmSettings(
                provider = FiscalFdmSettings.PROVIDER_MODULE2,
                host = "fdm.module2.be",
                port = 443,
                path = "/graphql/",
                useTls = true
            )
            save(migrated)
            return migrated
        }

        return FiscalFdmSettings(
            provider = storedProvider,
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 443).coerceIn(1, 65535),
            path = prefs.getString(KEY_PATH, "/graphql").orEmpty().ifBlank { "/graphql" },
            useTls = prefs.getBoolean(KEY_TLS, true)
        )
    }

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
