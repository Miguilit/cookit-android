package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.DiscoveredPrinter
import be.cookit.pos.android.domain.StarInterfaceType
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarDeviceDiscoveryManager
import com.starmicronics.stario10.StarDeviceDiscoveryManagerFactory
import com.starmicronics.stario10.StarPrinter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class StarDiscoveryService(context: Context) {
    private val appContext = context.applicationContext

    suspend fun discover(interfaceType: StarInterfaceType): List<DiscoveredPrinter> =
        suspendCancellableCoroutine { continuation ->
            val found = linkedMapOf<String, DiscoveredPrinter>()
            val interfaces = when (interfaceType) {
                StarInterfaceType.LAN -> listOf(InterfaceType.Lan)
                StarInterfaceType.BLUETOOTH -> listOf(InterfaceType.Bluetooth)
                StarInterfaceType.BLUETOOTH_LE -> listOf(InterfaceType.BluetoothLE)
                StarInterfaceType.USB -> listOf(InterfaceType.Usb)
            }

            val manager = runCatching {
                StarDeviceDiscoveryManagerFactory.create(interfaces, appContext)
            }.getOrElse { error ->
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }

            manager.discoveryTime = 8_000
            manager.callback = object : StarDeviceDiscoveryManager.Callback {
                override fun onPrinterFound(printer: StarPrinter) {
                    val settings = printer.connectionSettings
                    val mapped = when (settings.interfaceType) {
                        InterfaceType.Lan -> StarInterfaceType.LAN
                        InterfaceType.Bluetooth -> StarInterfaceType.BLUETOOTH
                        InterfaceType.BluetoothLE -> StarInterfaceType.BLUETOOTH_LE
                        InterfaceType.Usb -> StarInterfaceType.USB
                        else -> interfaceType
                    }
                    val identifier = settings.identifier.trim()
                    if (identifier.isNotEmpty()) {
                        found["${mapped.name}:$identifier"] = DiscoveredPrinter(identifier, mapped)
                    }
                }

                override fun onDiscoveryFinished() {
                    if (continuation.isActive) continuation.resume(found.values.toList())
                }
            }

            continuation.invokeOnCancellation {
                runCatching { manager.stopDiscovery() }
            }

            runCatching { manager.startDiscovery() }
                .onFailure { error ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
        }
}
