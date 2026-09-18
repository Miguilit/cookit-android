package be.cookit.pos.android.data

import android.content.Context

class PrinterSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_printer_settings", Context.MODE_PRIVATE)

    fun host(): String = prefs.getString("escpos_host", "") ?: ""
    fun port(): Int = prefs.getInt("escpos_port", 9100)

    fun save(host: String, port: Int) {
        prefs.edit()
            .putString("escpos_host", host.trim())
            .putInt("escpos_port", port.coerceIn(1, 65535))
            .apply()
    }
}
