package be.cookit.pos.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class EscPosPrinterService {
    suspend fun test(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), 2500)
            true
        }
    }

    suspend fun pulseDrawer(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), 2500)
            socket.getOutputStream().use { out ->
                // ESC p m t1 t2 — drawer kick pulse, drawer pin 2.
                out.write(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte()))
                out.flush()
            }
            true
        }
    }
}
