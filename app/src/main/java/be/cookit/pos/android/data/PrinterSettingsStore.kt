package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.PrinterProviderType
import be.cookit.pos.android.domain.StarInterfaceType

class PrinterSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_printer_settings", Context.MODE_PRIVATE)

    fun provider(): PrinterProviderType =
        runCatching { PrinterProviderType.valueOf(prefs.getString("provider", PrinterProviderType.ESC_POS.name)!!) }
            .getOrDefault(PrinterProviderType.ESC_POS)

    fun host(): String = prefs.getString("escpos_host", "") ?: ""
    fun port(): Int = prefs.getInt("escpos_port", 9100)

    fun starIdentifier(): String = prefs.getString("star_identifier", "") ?: ""

    fun starInterface(): StarInterfaceType =
        runCatching { StarInterfaceType.valueOf(prefs.getString("star_interface", StarInterfaceType.LAN.name)!!) }
            .getOrDefault(StarInterfaceType.LAN)

    fun saveEscPos(host: String, port: Int) {
        prefs.edit()
            .putString("escpos_host", host.trim())
            .putInt("escpos_port", port.coerceIn(1, 65535))
            .apply()
    }

    fun saveProvider(provider: PrinterProviderType) {
        prefs.edit().putString("provider", provider.name).apply()
    }

    fun saveStar(identifier: String, interfaceType: StarInterfaceType) {
        prefs.edit()
            .putString("star_identifier", identifier.trim())
            .putString("star_interface", interfaceType.name)
            .apply()
    }

    // Compatibility with A6/A7 caller.
    fun save(host: String, port: Int) = saveEscPos(host, port)
}
