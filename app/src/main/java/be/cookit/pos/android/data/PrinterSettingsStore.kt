package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.HardwareMode
import be.cookit.pos.android.domain.HardwareSimulationEvent
import be.cookit.pos.android.domain.PrinterProviderType
import be.cookit.pos.android.domain.StarInterfaceType
import org.json.JSONArray
import org.json.JSONObject

class PrinterSettingsStore(
    context: Context
) {
    private val prefs =
        context.getSharedPreferences(
            "cookit_printer_settings",
            Context.MODE_PRIVATE
        )

    fun provider(): PrinterProviderType =
        runCatching {
            PrinterProviderType.valueOf(
                prefs.getString(
                    "provider",
                    PrinterProviderType.ESC_POS.name
                )!!
            )
        }.getOrDefault(
            PrinterProviderType.ESC_POS
        )

    fun host(): String =
        prefs.getString(
            "escpos_host",
            ""
        ) ?: ""

    fun port(): Int =
        prefs.getInt(
            "escpos_port",
            9100
        )

    fun starIdentifier(): String =
        prefs.getString(
            "star_identifier",
            ""
        ) ?: ""

    fun starInterface(): StarInterfaceType =
        runCatching {
            StarInterfaceType.valueOf(
                prefs.getString(
                    "star_interface",
                    StarInterfaceType.LAN.name
                )!!
            )
        }.getOrDefault(
            StarInterfaceType.LAN
        )

    /*
     * REAL is deliberately the fail-safe default.
     * Simulation must always be explicitly enabled locally.
     */
    fun hardwareMode(): HardwareMode =
        runCatching {
            HardwareMode.valueOf(
                prefs.getString(
                    "hardware_mode",
                    HardwareMode.REAL.name
                )!!
            )
        }.getOrDefault(
            HardwareMode.REAL
        )

    fun lastSimulationPreview(): String? =
        prefs.getString(
            "simulation_preview",
            null
        )
            ?.takeIf {
                it.isNotBlank()
            }

    fun simulationEvents(): List<HardwareSimulationEvent> {
        val raw =
            prefs.getString(
                "simulation_events",
                "[]"
            ) ?: "[]"

        return runCatching {
            val array =
                JSONArray(
                    raw
                )

            buildList {
                for (
                    index in 0 until array.length()
                ) {
                    val obj =
                        array.optJSONObject(
                            index
                        ) ?: continue

                    val timestamp =
                        obj.optLong(
                            "timestamp_epoch_ms",
                            0L
                        )

                    val action =
                        obj.optString(
                            "action",
                            ""
                        )

                    val result =
                        obj.optString(
                            "result",
                            ""
                        )

                    val detail =
                        obj.optString(
                            "detail",
                            ""
                        )
                            .takeIf {
                                it.isNotBlank()
                                && it != "null"
                            }

                    if (
                        timestamp > 0L
                        && action.isNotBlank()
                    ) {
                        add(
                            HardwareSimulationEvent(
                                timestampEpochMs =
                                    timestamp,
                                action =
                                    action,
                                result =
                                    result,
                                detail =
                                    detail
                            )
                        )
                    }
                }
            }
        }.getOrDefault(
            emptyList()
        )
    }

    fun saveEscPos(
        host: String,
        port: Int
    ) {
        prefs
            .edit()
            .putString(
                "escpos_host",
                host.trim()
            )
            .putInt(
                "escpos_port",
                port.coerceIn(
                    1,
                    65535
                )
            )
            .apply()
    }

    fun saveProvider(
        provider: PrinterProviderType
    ) {
        prefs
            .edit()
            .putString(
                "provider",
                provider.name
            )
            .apply()
    }

    fun saveStar(
        identifier: String,
        interfaceType: StarInterfaceType
    ) {
        prefs
            .edit()
            .putString(
                "star_identifier",
                identifier.trim()
            )
            .putString(
                "star_interface",
                interfaceType.name
            )
            .apply()
    }

    fun saveHardwareMode(
        mode: HardwareMode
    ) {
        prefs
            .edit()
            .putString(
                "hardware_mode",
                mode.name
            )
            .apply()
    }

    fun appendSimulationEvent(
        action: String,
        result: String,
        detail: String? = null,
        previewText: String? = null
    ) {
        val events =
            simulationEvents()
                .toMutableList()

        events +=
            HardwareSimulationEvent(
                timestampEpochMs =
                    System.currentTimeMillis(),
                action =
                    action.trim(),
                result =
                    result.trim(),
                detail =
                    detail
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
            )

        while (
            events.size >
            MAX_SIMULATION_EVENTS
        ) {
            events.removeAt(
                0
            )
        }

        val array =
            JSONArray()

        events.forEach {
            event ->

            array.put(
                JSONObject()
                    .put(
                        "timestamp_epoch_ms",
                        event.timestampEpochMs
                    )
                    .put(
                        "action",
                        event.action
                    )
                    .put(
                        "result",
                        event.result
                    )
                    .put(
                        "detail",
                        event.detail
                            ?: JSONObject.NULL
                    )
            )
        }

        val editor =
            prefs
                .edit()
                .putString(
                    "simulation_events",
                    array.toString()
                )

        if (
            previewText != null
        ) {
            editor.putString(
                "simulation_preview",
                previewText
            )
        }

        editor.apply()
    }

    fun clearSimulationHistory() {
        prefs
            .edit()
            .remove(
                "simulation_events"
            )
            .apply()
    }

    fun clearSimulationPreview() {
        prefs
            .edit()
            .remove(
                "simulation_preview"
            )
            .apply()
    }

    /*
     * Compatibility with A6/A7 caller.
     */
    fun save(
        host: String,
        port: Int
    ) =
        saveEscPos(
            host,
            port
        )

    private companion object {
        const val MAX_SIMULATION_EVENTS =
            50
    }
}
