package be.cookit.pos.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class EscPosPrinterService {
    suspend fun test(
        host: String,
        port: Int
    ): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            if (
                host.isBlank()
            ) {
                return@withContext false
            }

            Socket().use {
                socket ->
                socket.connect(
                    InetSocketAddress(
                        host.trim(),
                        port
                    ),
                    2500
                )

                true
            }
        }

    suspend fun pulseDrawer(
        host: String,
        port: Int
    ): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            if (
                host.isBlank()
            ) {
                return@withContext false
            }

            Socket().use {
                socket ->
                socket.connect(
                    InetSocketAddress(
                        host.trim(),
                        port
                    ),
                    2500
                )

                socket
                    .getOutputStream()
                    .use {
                        out ->
                        /*
                         * ESC p m t1 t2
                         * Drawer kick pulse, drawer pin 2.
                         */
                        out.write(
                            byteArrayOf(
                                0x1B,
                                0x70,
                                0x00,
                                0x19,
                                0xFA.toByte()
                            )
                        )

                        out.flush()
                    }

                true
            }
        }

    suspend fun printText(
        host: String,
        port: Int,
        text: String,
        cut: Boolean = true
    ): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            if (
                host.isBlank()
                || text.isBlank()
            ) {
                return@withContext false
            }

            Socket().use {
                socket ->
                socket.connect(
                    InetSocketAddress(
                        host.trim(),
                        port
                    ),
                    2500
                )

                socket
                    .getOutputStream()
                    .use {
                        out ->

                        /*
                         * ESC @ — initialize printer.
                         */
                        out.write(
                            byteArrayOf(
                                0x1B,
                                0x40
                            )
                        )

                        out.write(
                            text
                                .trimEnd()
                                .plus(
                                    "\n\n"
                                )
                                .toByteArray(
                                    StandardCharsets.UTF_8
                                )
                        )

                        /*
                         * Feed before cut.
                         */
                        out.write(
                            byteArrayOf(
                                0x1B,
                                0x64,
                                0x03
                            )
                        )

                        if (
                            cut
                        ) {
                            /*
                             * GS V 66 0 — partial cut.
                             */
                            out.write(
                                byteArrayOf(
                                    0x1D,
                                    0x56,
                                    0x42,
                                    0x00
                                )
                            )
                        }

                        out.flush()
                    }

                true
            }
        }
}
