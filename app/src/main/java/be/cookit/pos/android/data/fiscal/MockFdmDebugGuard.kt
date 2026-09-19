package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.BuildConfig

/** Debug-only escape hatch used exclusively by the A14.4 Mock FDM harness. */
object MockFdmDebugGuard {
    fun cleartextAllowed(settings: FiscalFdmSettings): Boolean {
        if (!BuildConfig.ENABLE_MOCK_FDM || !settings.isMock || settings.useTls) return false
        return isPrivateLiteral(settings.host)
    }

    fun requireAllowed(settings: FiscalFdmSettings) {
        check(cleartextAllowed(settings)) {
            "Clear-text FDM transport is allowed only for the debug Mock FDM on a private/loopback host."
        }
    }

    private fun isPrivateLiteral(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host == "::1" || host.startsWith("fe80:")) return true
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        if (!host.startsWith("172.")) return false
        val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
}
