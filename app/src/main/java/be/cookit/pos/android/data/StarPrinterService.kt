package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.StarInterfaceType
import com.starmicronics.stario10.InterfaceType
import com.starmicronics.stario10.StarConnectionSettings
import com.starmicronics.stario10.StarPrinter
import com.starmicronics.stario10.starxpandcommand.DocumentBuilder
import com.starmicronics.stario10.starxpandcommand.DrawerBuilder
import com.starmicronics.stario10.starxpandcommand.PrinterBuilder
import com.starmicronics.stario10.starxpandcommand.StarXpandCommandBuilder
import com.starmicronics.stario10.starxpandcommand.drawer.OpenParameter
import com.starmicronics.stario10.starxpandcommand.printer.CutType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

class StarPrinterService(
    context: Context
) {
    private val appContext =
        context.applicationContext

    suspend fun test(
        identifier: String,
        interfaceType: StarInterfaceType
    ): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            if (
                identifier.isBlank()
            ) {
                return@withContext false
            }

            withPrinter(
                identifier,
                interfaceType
            ) {
                printer ->
                printer
                    .getStatusAsync()
                    .await()

                true
            }
        }

    suspend fun pulseDrawer(
        identifier: String,
        interfaceType: StarInterfaceType
    ): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            if (
                identifier.isBlank()
            ) {
                return@withContext false
            }

            val commands =
                StarXpandCommandBuilder()
                    .addDocument(
                        DocumentBuilder()
                            .addDrawer(
                                DrawerBuilder()
                                    .actionOpen(
                                        OpenParameter()
                                    )
                            )
                    )
                    .getCommands()

            withPrinter(
                identifier,
                interfaceType
            ) {
                printer ->
                printer
                    .printAsync(
                        commands
                    )
                    .await()

                true
            }
        }

    suspend fun printText(
        identifier: String,
        interfaceType: StarInterfaceType,
        text: String,
        cut: Boolean = true
    ): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            if (
                identifier.isBlank()
                || text.isBlank()
            ) {
                return@withContext false
            }

            val printerBuilder =
                PrinterBuilder()
                    .actionPrintText(
                        text
                            .trimEnd()
                            .plus(
                                "\n\n"
                            )
                    )
                    .actionFeedLine(
                        2
                    )

            if (
                cut
            ) {
                printerBuilder.actionCut(
                    CutType.Partial
                )
            }

            val commands =
                StarXpandCommandBuilder()
                    .addDocument(
                        DocumentBuilder()
                            .addPrinter(
                                printerBuilder
                            )
                    )
                    .getCommands()

            withPrinter(
                identifier,
                interfaceType
            ) {
                printer ->
                printer
                    .printAsync(
                        commands
                    )
                    .await()

                true
            }
        }

    private suspend fun <T> withPrinter(
        identifier: String,
        interfaceType: StarInterfaceType,
        block: suspend (
            StarPrinter
        ) -> T
    ): T {
        val settings =
            StarConnectionSettings(
                when (
                    interfaceType
                ) {
                    StarInterfaceType.LAN ->
                        InterfaceType.Lan

                    StarInterfaceType.BLUETOOTH ->
                        InterfaceType.Bluetooth

                    StarInterfaceType.BLUETOOTH_LE ->
                        InterfaceType.BluetoothLE

                    StarInterfaceType.USB ->
                        InterfaceType.Usb
                },
                identifier.trim()
            )

        val printer =
            StarPrinter(
                settings,
                appContext
            )

        printer
            .openAsync()
            .await()

        return try {
            block(
                printer
            )
        } finally {
            runCatching {
                printer
                    .closeAsync()
                    .await()
            }
        }
    }
}
