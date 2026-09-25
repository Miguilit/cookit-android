package be.cookit.pos.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import be.cookit.pos.android.domain.AppLanguage
import be.cookit.pos.android.domain.CashDenomination
import be.cookit.pos.android.domain.CashMovement
import kotlinx.coroutines.delay
import java.util.Locale

private val CashOrange = Color(0xFFF97316)
private val CashOrangeSoft = Color(0xFFFFF3E8)
private val CashGreen = Color(0xFF15803D)
private val CashGreenSoft = Color(0xFFEAF7EE)
private val CashBlueSoft = Color(0xFFEAF3FF)
private val CashCanvas = Color(0xFFF7F8FA)
private val CashLine = Color(0xFFE6E8EC)
private val CashMuted = Color(0xFF68707C)

private data class CashManagerStrings(
    val managerTitle: String,
    val managerHelp: String,
    val approve: String,
    val reject: String,
    val approveTitle: String,
    val approveMessage: String,
    val rejectTitle: String,
    val rejectMessage: String,
    val cancel: String,
    val reportsTitle: String,
    val reportsHelp: String,
    val xReport: String,
    val zReport: String,
    val reportTitleX: String,
    val reportTitleZ: String,
    val print: String,
    val close: String,
    val session: String,
    val register: String,
    val cashier: String,
    val opened: String,
    val closed: String,
    val generated: String,
    val opening: String,
    val cashSales: String,
    val totalPayments: String,
    val changeGiven: String,
    val cashIn: String,
    val cashOut: String,
    val safeDrop: String,
    val refunds: String,
    val expected: String,
    val physical: String,
    val counted: String,
    val discrepancy: String,
    val paymentMethods: String,
    val denominations: String,
    val approved: String,
    val rejected: String,
    val approvalForbidden: String,
    val sessionNotPending: String,
    val reportForbidden: String,
    val reportSessionMissing: String,
    val deviceMissing: String,
    val printing: String,
    val printed: String,
    val printFailed: String,
    val reportLoading: String,
    val reportUnavailable: String
)

private fun cashManagerStrings(
    language: AppLanguage
): CashManagerStrings =
    when (
        language
    ) {
        AppLanguage.NL ->
            CashManagerStrings(
                managerTitle = "Managergoedkeuring",
                managerHelp = "De kassasluiting wacht op controle door een bevoegde manager.",
                approve = "Sluiting goedkeuren",
                reject = "Terugsturen",
                approveTitle = "Kassasluiting goedkeuren?",
                approveMessage = "Dit sluit de fiscale shift definitief en laat de backend de ladeverklaring voorbereiden.",
                rejectTitle = "Sluiting terugsturen?",
                rejectMessage = "De kassasessie wordt opnieuw geopend. De ingediende telling wordt verwijderd.",
                cancel = "Annuleren",
                reportsTitle = "Kassarapporten",
                reportsHelp = "X is een momentopname van een open shift. Z is alleen beschikbaar na een bevoegde sluiting.",
                xReport = "X-rapport",
                zReport = "Z-rapport",
                reportTitleX = "X-rapport",
                reportTitleZ = "Z-rapport",
                print = "Afdrukken",
                close = "Sluiten",
                session = "Sessie",
                register = "Kassa",
                cashier = "Kassier",
                opened = "Geopend",
                closed = "Gesloten",
                generated = "Gegenereerd",
                opening = "Beginfonds",
                cashSales = "Contante verkopen",
                totalPayments = "Totale betalingen",
                changeGiven = "Wisselgeld",
                cashIn = "Kas in",
                cashOut = "Kas uit",
                safeDrop = "Veilige afstorting",
                refunds = "Terugbetalingen",
                expected = "Verwachte kas",
                physical = "Fysiek geteld",
                counted = "Geteld totaal",
                discrepancy = "Verschil",
                paymentMethods = "Betaalmethoden",
                denominations = "Coupures",
                approved = "Kassasluiting goedgekeurd.",
                rejected = "Sluiting teruggestuurd naar de kassier.",
                approvalForbidden = "Je hebt geen toestemming om kassasluitingen goed te keuren.",
                sessionNotPending = "Deze sessie wacht niet meer op goedkeuring.",
                reportForbidden = "Je hebt geen toestemming om kassarapporten te bekijken.",
                reportSessionMissing = "Geen geschikte kassasessie beschikbaar voor dit rapport.",
                deviceMissing = "De permanente identiteit van dit Android POS ontbreekt.",
                printing = "Rapport wordt afgedrukt…",
                printed = "Rapport afgedrukt.",
                printFailed = "Afdrukken van het rapport is mislukt.",
                reportLoading = "Rapport wordt geladen…",
                reportUnavailable = "Het kassarapport is momenteel niet beschikbaar."
            )

        AppLanguage.EN ->
            CashManagerStrings(
                managerTitle = "Manager approval",
                managerHelp = "This register closing is waiting for review by an authorised manager.",
                approve = "Approve closing",
                reject = "Send back",
                approveTitle = "Approve register closing?",
                approveMessage = "This definitively closes the fiscal shift and allows the backend to prepare the drawer declaration.",
                rejectTitle = "Send closing back?",
                rejectMessage = "The cash session will reopen and the submitted physical count will be discarded.",
                cancel = "Cancel",
                reportsTitle = "Cash reports",
                reportsHelp = "X is a read-only snapshot of an open shift. Z is available only after an authoritative close.",
                xReport = "X report",
                zReport = "Z report",
                reportTitleX = "X report",
                reportTitleZ = "Z report",
                print = "Print",
                close = "Close",
                session = "Session",
                register = "Register",
                cashier = "Cashier",
                opened = "Opened",
                closed = "Closed",
                generated = "Generated",
                opening = "Opening float",
                cashSales = "Cash sales",
                totalPayments = "Total payments",
                changeGiven = "Change given",
                cashIn = "Cash in",
                cashOut = "Cash out",
                safeDrop = "Safe drop",
                refunds = "Refunds",
                expected = "Expected cash",
                physical = "Physical cash",
                counted = "Counted total",
                discrepancy = "Discrepancy",
                paymentMethods = "Payment methods",
                denominations = "Denominations",
                approved = "Register closing approved.",
                rejected = "Closing sent back to the cashier.",
                approvalForbidden = "You do not have permission to approve cash-register closings.",
                sessionNotPending = "This session is no longer awaiting approval.",
                reportForbidden = "You do not have permission to view cash-register reports.",
                reportSessionMissing = "No eligible cash-register session is available for this report.",
                deviceMissing = "The permanent identity of this Android POS is unavailable.",
                printing = "Printing report…",
                printed = "Report printed.",
                printFailed = "Report printing failed.",
                reportLoading = "Loading report…",
                reportUnavailable = "The cash-register report is currently unavailable."
            )

        AppLanguage.DE ->
            CashManagerStrings(
                managerTitle = "Managerfreigabe",
                managerHelp = "Dieser Kassenabschluss wartet auf die Prüfung durch einen berechtigten Manager.",
                approve = "Abschluss freigeben",
                reject = "Zurückgeben",
                approveTitle = "Kassenabschluss freigeben?",
                approveMessage = "Dadurch wird die Fiskalschicht endgültig geschlossen und das Backend kann die Kassenschubladendeklaration vorbereiten.",
                rejectTitle = "Abschluss zurückgeben?",
                rejectMessage = "Die Kassensitzung wird wieder geöffnet und die eingereichte physische Zählung wird verworfen.",
                cancel = "Abbrechen",
                reportsTitle = "Kassenberichte",
                reportsHelp = "X ist eine schreibgeschützte Momentaufnahme einer offenen Schicht. Z ist erst nach einem autoritativen Abschluss verfügbar.",
                xReport = "X-Bericht",
                zReport = "Z-Bericht",
                reportTitleX = "X-Bericht",
                reportTitleZ = "Z-Bericht",
                print = "Drucken",
                close = "Schließen",
                session = "Sitzung",
                register = "Kasse",
                cashier = "Kassierer",
                opened = "Geöffnet",
                closed = "Geschlossen",
                generated = "Erstellt",
                opening = "Anfangsbestand",
                cashSales = "Barverkäufe",
                totalPayments = "Zahlungen gesamt",
                changeGiven = "Wechselgeld",
                cashIn = "Einzahlung",
                cashOut = "Auszahlung",
                safeDrop = "Tresoreinwurf",
                refunds = "Rückerstattungen",
                expected = "Erwarteter Bargeldbestand",
                physical = "Physisch gezählt",
                counted = "Gezählter Gesamtbetrag",
                discrepancy = "Differenz",
                paymentMethods = "Zahlungsarten",
                denominations = "Stückelungen",
                approved = "Kassenabschluss freigegeben.",
                rejected = "Abschluss an den Kassierer zurückgegeben.",
                approvalForbidden = "Du hast keine Berechtigung, Kassenabschlüsse freizugeben.",
                sessionNotPending = "Diese Sitzung wartet nicht mehr auf eine Freigabe.",
                reportForbidden = "Du hast keine Berechtigung, Kassenberichte anzuzeigen.",
                reportSessionMissing = "Für diesen Bericht ist keine geeignete Kassensitzung verfügbar.",
                deviceMissing = "Die permanente Identität dieses Android-POS ist nicht verfügbar.",
                printing = "Bericht wird gedruckt…",
                printed = "Bericht gedruckt.",
                printFailed = "Drucken des Berichts fehlgeschlagen.",
                reportLoading = "Bericht wird geladen…",
                reportUnavailable = "Der Kassenbericht ist derzeit nicht verfügbar."
            )

        else ->
            CashManagerStrings(
                managerTitle = "Validation manager",
                managerHelp = "Cette clôture de caisse attend le contrôle d’un manager autorisé.",
                approve = "Approuver la clôture",
                reject = "Renvoyer au caissier",
                approveTitle = "Approuver la clôture de caisse ?",
                approveMessage = "Cette action ferme définitivement le shift fiscal et autorise le backend à préparer la déclaration de tiroir.",
                rejectTitle = "Renvoyer cette clôture ?",
                rejectMessage = "La session de caisse sera rouverte et le comptage physique soumis sera supprimé.",
                cancel = "Annuler",
                reportsTitle = "Rapports de caisse",
                reportsHelp = "X est une photographie en lecture seule du shift ouvert. Z n’est disponible qu’après une clôture autoritative.",
                xReport = "Rapport X",
                zReport = "Rapport Z",
                reportTitleX = "Rapport X",
                reportTitleZ = "Rapport Z",
                print = "Imprimer",
                close = "Fermer",
                session = "Session",
                register = "Caisse",
                cashier = "Caissier",
                opened = "Ouverte",
                closed = "Fermée",
                generated = "Généré",
                opening = "Fond de caisse",
                cashSales = "Ventes espèces",
                totalPayments = "Paiements totaux",
                changeGiven = "Monnaie rendue",
                cashIn = "Entrée espèces",
                cashOut = "Sortie espèces",
                safeDrop = "Dépôt coffre",
                refunds = "Remboursements",
                expected = "Espèces attendues",
                physical = "Espèces physiques",
                counted = "Total compté",
                discrepancy = "Écart",
                paymentMethods = "Moyens de paiement",
                denominations = "Coupures",
                approved = "Clôture de caisse approuvée.",
                rejected = "Clôture renvoyée au caissier.",
                approvalForbidden = "Vous n’avez pas la permission d’approuver les clôtures de caisse.",
                sessionNotPending = "Cette session n’attend plus de validation.",
                reportForbidden = "Vous n’avez pas la permission de consulter les rapports de caisse.",
                reportSessionMissing = "Aucune session de caisse éligible n’est disponible pour ce rapport.",
                deviceMissing = "L’identité permanente de cette caisse Android est indisponible.",
                printing = "Impression du rapport…",
                printed = "Rapport imprimé.",
                printFailed = "Échec de l’impression du rapport.",
                reportLoading = "Chargement du rapport…",
                reportUnavailable = "Le rapport de caisse est momentanément indisponible."
            )
    }

private fun cashReportErrorText(
    value: String,
    strings: CashManagerStrings
): String =
    when (
        value
    ) {
        "cash_report_forbidden" ->
            strings.reportForbidden

        "cash_report_session_missing" ->
            strings.reportSessionMissing

        "cash_device_identity_missing" ->
            strings.deviceMissing

        "cash_report_type_invalid" ->
            strings.reportUnavailable

        else ->
            strings.reportUnavailable
    }

private data class CashRegisterStrings(
    val title: String,
    val subtitle: String,
    val noSession: String,
    val openingTitle: String,
    val openingHelp: String,
    val register: String,
    val openingFloat: String,
    val start: String,
    val session: String,
    val sessionOpen: String,
    val pendingApproval: String,
    val pendingHelp: String,
    val opening: String,
    val expected: String,
    val cashIn: String,
    val cashOut: String,
    val safeDrop: String,
    val drawer: String,
    val close: String,
    val movements: String,
    val noMovements: String,
    val amount: String,
    val reason: String,
    val cancel: String,
    val confirm: String,
    val closeTitle: String,
    val closeHelp: String,
    val counted: String,
    val difference: String,
    val closingNote: String,
    val submitClose: String,
    val denominationsMissing: String,
    val registerMissing: String,
    val sessionNotOpen: String,
    val invalidAmount: String,
    val opened: String,
    val movementSaved: String,
    val closingSubmitted: String,
    val refresh: String,
    val cashSale: String,
    val refund: String,
    val transactions: String
)

private fun cashStrings(
    language: AppLanguage
): CashRegisterStrings =
    when (language) {
        AppLanguage.FR ->
            CashRegisterStrings(
                title = "Caisse",
                subtitle = "Session et mouvements du tiroir",
                noSession = "Aucune session active",
                openingTitle = "Ouverture de caisse",
                openingHelp = "Comptez le fond initial ou saisissez directement son montant.",
                register = "Caisse",
                openingFloat = "Fond initial",
                start = "Démarrer le service",
                session = "Session",
                sessionOpen = "Ouverte",
                pendingApproval = "Clôture en attente d’approbation",
                pendingHelp = "La session est verrouillée jusqu’à la décision du responsable.",
                opening = "Fond initial",
                expected = "Espèces attendues",
                cashIn = "Entrée d’espèces",
                cashOut = "Sortie d’espèces",
                safeDrop = "Dépôt coffre",
                drawer = "Ouvrir le tiroir",
                close = "Clôturer la caisse",
                movements = "Mouvements récents",
                noMovements = "Aucun mouvement pour cette session.",
                amount = "Montant",
                reason = "Motif",
                cancel = "Annuler",
                confirm = "Confirmer",
                closeTitle = "Comptage de clôture",
                closeHelp = "Comptez uniquement les espèces physiquement présentes dans le tiroir.",
                counted = "Espèces comptées",
                difference = "Écart",
                closingNote = "Note de clôture",
                submitClose = "Soumettre la clôture",
                denominationsMissing = "Aucune coupure n’est configurée pour cette branche. Configurez les dénominations dans Cookit Cloud avant de clôturer.",
                registerMissing = "Aucune caisse active n’est configurée pour cette branche.",
                sessionNotOpen = "La session de caisse n’est pas ouverte.",
                invalidAmount = "Saisissez un montant supérieur à zéro.",
                opened = "Session de caisse ouverte.",
                movementSaved = "Mouvement enregistré.",
                closingSubmitted = "Clôture envoyée pour approbation.",
                refresh = "Rafraîchir",
                cashSale = "Ventes espèces",
                refund = "Remboursements",
                transactions = "opérations"
            )

        AppLanguage.NL ->
            CashRegisterStrings(
                title = "Kassa",
                subtitle = "Sessie en kassaladebewegingen",
                noSession = "Geen actieve kassasessie",
                openingTitle = "Kassa openen",
                openingHelp = "Tel het startgeld of voer het bedrag rechtstreeks in.",
                register = "Kassa",
                openingFloat = "Startgeld",
                start = "Dienst starten",
                session = "Sessie",
                sessionOpen = "Open",
                pendingApproval = "Afsluiting wacht op goedkeuring",
                pendingHelp = "De sessie blijft vergrendeld tot een verantwoordelijke beslist.",
                opening = "Startgeld",
                expected = "Verwacht contant",
                cashIn = "Kasstorting",
                cashOut = "Kasuitgave",
                safeDrop = "Kluisstorting",
                drawer = "Kassalade openen",
                close = "Kassa afsluiten",
                movements = "Recente bewegingen",
                noMovements = "Geen bewegingen voor deze sessie.",
                amount = "Bedrag",
                reason = "Reden",
                cancel = "Annuleren",
                confirm = "Bevestigen",
                closeTitle = "Eindtelling",
                closeHelp = "Tel uitsluitend het geld dat fysiek in de kassalade aanwezig is.",
                counted = "Geteld contant",
                difference = "Verschil",
                closingNote = "Afsluitnota",
                submitClose = "Afsluiting indienen",
                denominationsMissing = "Voor deze vestiging zijn geen coupures ingesteld. Configureer de coupures in Cookit Cloud voordat u afsluit.",
                registerMissing = "Voor deze vestiging is geen actieve kassa ingesteld.",
                sessionNotOpen = "De kassasessie is niet open.",
                invalidAmount = "Voer een bedrag groter dan nul in.",
                opened = "Kassasessie geopend.",
                movementSaved = "Beweging opgeslagen.",
                closingSubmitted = "Afsluiting ter goedkeuring ingediend.",
                refresh = "Vernieuwen",
                cashSale = "Contante verkoop",
                refund = "Terugbetalingen",
                transactions = "transacties"
            )

        AppLanguage.EN ->
            CashRegisterStrings(
                title = "Cash register",
                subtitle = "Drawer session and cash movements",
                noSession = "No active cash session",
                openingTitle = "Open cash register",
                openingHelp = "Count the opening float or enter its amount directly.",
                register = "Register",
                openingFloat = "Opening float",
                start = "Start service",
                session = "Session",
                sessionOpen = "Open",
                pendingApproval = "Closing awaiting approval",
                pendingHelp = "The session is locked until a manager makes a decision.",
                opening = "Opening float",
                expected = "Expected cash",
                cashIn = "Cash in",
                cashOut = "Cash out",
                safeDrop = "Safe drop",
                drawer = "Open drawer",
                close = "Close register",
                movements = "Recent movements",
                noMovements = "No movements for this session.",
                amount = "Amount",
                reason = "Reason",
                cancel = "Cancel",
                confirm = "Confirm",
                closeTitle = "Closing count",
                closeHelp = "Count only the physical cash currently present in the drawer.",
                counted = "Counted cash",
                difference = "Difference",
                closingNote = "Closing note",
                submitClose = "Submit closing",
                denominationsMissing = "No denominations are configured for this branch. Configure denominations in Cookit Cloud before closing.",
                registerMissing = "No active cash register is configured for this branch.",
                sessionNotOpen = "The cash session is not open.",
                invalidAmount = "Enter an amount greater than zero.",
                opened = "Cash session opened.",
                movementSaved = "Movement recorded.",
                closingSubmitted = "Closing submitted for approval.",
                refresh = "Refresh",
                cashSale = "Cash sales",
                refund = "Refunds",
                transactions = "transactions"
            )

        AppLanguage.DE ->
            CashRegisterStrings(
                title = "Kasse",
                subtitle = "Kassensitzung und Bargeldbewegungen",
                noSession = "Keine aktive Kassensitzung",
                openingTitle = "Kasse öffnen",
                openingHelp = "Zählen Sie den Anfangsbestand oder geben Sie den Betrag direkt ein.",
                register = "Kasse",
                openingFloat = "Anfangsbestand",
                start = "Service starten",
                session = "Sitzung",
                sessionOpen = "Offen",
                pendingApproval = "Abschluss wartet auf Freigabe",
                pendingHelp = "Die Sitzung bleibt bis zur Entscheidung einer verantwortlichen Person gesperrt.",
                opening = "Anfangsbestand",
                expected = "Erwartetes Bargeld",
                cashIn = "Bareinlage",
                cashOut = "Barausgabe",
                safeDrop = "Tresoreinlage",
                drawer = "Kassenschublade öffnen",
                close = "Kasse schließen",
                movements = "Letzte Bewegungen",
                noMovements = "Keine Bewegungen für diese Sitzung.",
                amount = "Betrag",
                reason = "Grund",
                cancel = "Abbrechen",
                confirm = "Bestätigen",
                closeTitle = "Abschlusszählung",
                closeHelp = "Zählen Sie ausschließlich das physisch in der Kassenschublade vorhandene Bargeld.",
                counted = "Gezähltes Bargeld",
                difference = "Differenz",
                closingNote = "Abschlussnotiz",
                submitClose = "Abschluss einreichen",
                denominationsMissing = "Für diese Filiale sind keine Stückelungen eingerichtet. Konfigurieren Sie die Stückelungen in Cookit Cloud, bevor Sie die Kasse schließen.",
                registerMissing = "Für diese Filiale ist keine aktive Kasse eingerichtet.",
                sessionNotOpen = "Die Kassensitzung ist nicht geöffnet.",
                invalidAmount = "Geben Sie einen Betrag größer als null ein.",
                opened = "Kassensitzung geöffnet.",
                movementSaved = "Bewegung gespeichert.",
                closingSubmitted = "Abschluss zur Freigabe eingereicht.",
                refresh = "Aktualisieren",
                cashSale = "Barverkäufe",
                refund = "Erstattungen",
                transactions = "Transaktionen"
            )
    }

private enum class MovementAction {
    CASH_IN,
    CASH_OUT,
    SAFE_DROP
}

@Composable
fun PremiumCashRegisterScreen(
    state: PosUiState,
    vm: CookitPosViewModel
) {
    val s =
        cashStrings(
            state.language
        )

    val managerStrings =
        cashManagerStrings(
            state.language
        )

    val hardwareStrings =
        hardwareSimulationStrings(
            state.language
        )

    var hardwarePreviewOpen by
        remember {
            mutableStateOf(
                false
            )
        }

    LaunchedEffect(
        state.cashReportPrintMessage,
        state.hardwareSimulationPreview
    ) {
        if (
            state.hardwareMode ==
                be.cookit.pos.android.domain.HardwareMode.SIMULATED
            && state.cashReportPrintMessage ==
                "cash_report_printed_simulated"
            && ! state.hardwareSimulationPreview
                .isNullOrBlank()
        ) {
            hardwarePreviewOpen =
                true
        }
    }

    if (
        hardwarePreviewOpen
    ) {
        state.hardwareSimulationPreview
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                preview ->

                HardwareSimulationPreviewDialog(
                    preview =
                        preview,
                    strings =
                        hardwareStrings,
                    onDismiss = {
                        hardwarePreviewOpen =
                            false
                    }
                )
            }
    }

    var movementAction by
        remember {
            mutableStateOf<MovementAction?>(
                null
            )
        }

    var closeOpen by
        remember {
            mutableStateOf(
                false
            )
        }

    var approveConfirmOpen by
        remember {
            mutableStateOf(
                false
            )
        }

    var rejectConfirmOpen by
        remember {
            mutableStateOf(
                false
            )
        }

    LaunchedEffect(Unit) {
        vm.refreshCashRegister(
            silent = true
        )
    }

    LaunchedEffect(
        state.activeCashSession?.id,
        state.activeCashSession?.status
    ) {
        while (true) {
            delay(
                5_000
            )

            vm.refreshCashRegister(
                silent = true
            )
        }
    }

    val session =
        state.activeCashSession

    if (session == null) {
        Box(
            modifier =
                Modifier.fillMaxSize()
        ) {
            CashRegisterOpening(
                state = state,
                vm = vm,
                s = s
            )

            if (
                state.hardwareMode ==
                be.cookit.pos.android.domain.HardwareMode.SIMULATED
            ) {
                HardwareSimulationBadge(
                    modifier =
                        Modifier
                            .align(
                                Alignment.TopEnd
                            )
                            .padding(
                                20.dp
                            ),
                    strings =
                        hardwareStrings,
                    previewAvailable =
                        ! state.hardwareSimulationPreview
                            .isNullOrBlank(),
                    onPreview = {
                        hardwarePreviewOpen =
                            true
                    }
                )
            }

            val lastClosedSession =
                state.lastClosedCashSession

            if (
                state.policy.canViewCashRegisterReports
                && lastClosedSession != null
            ) {
                Surface(
                    modifier =
                        Modifier
                            .align(
                                Alignment.BottomEnd
                            )
                            .padding(
                                20.dp
                            ),
                    shape =
                        RoundedCornerShape(
                            20.dp
                        ),
                    color =
                        Color.White,
                    border =
                        BorderStroke(
                            1.dp,
                            CashLine
                        )
                ) {
                    Column(
                        modifier =
                            Modifier.padding(
                                16.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                10.dp
                            )
                    ) {
                        Text(
                            managerStrings.reportTitleZ,
                            fontWeight =
                                FontWeight.Black,
                            fontSize =
                                18.sp
                        )

                        Text(
                            "${managerStrings.session} #${lastClosedSession.id}",
                            color =
                                CashMuted
                        )

                        if (
                            state.cashActionMessage
                                == "cash_closing_approved"
                        ) {
                            Text(
                                managerStrings.approved,
                                color =
                                    CashGreen,
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                vm.loadCashReport(
                                    "z",
                                    lastClosedSession.id
                                )
                            },
                            enabled =
                                ! state.cashReportBusy
                        ) {
                            if (
                                state.cashReportBusy
                            ) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(
                                            18.dp
                                        )
                                )

                                Spacer(
                                    Modifier.width(
                                        8.dp
                                    )
                                )
                            }

                            Text(
                                managerStrings.zReport
                            )
                        }

                        state.cashReportError
                            ?.let {
                                error ->

                                Text(
                                    cashReportErrorText(
                                        error,
                                        managerStrings
                                    ),
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .error,
                                    fontSize =
                                        13.sp
                                )
                            }
                    }
                }
            }
        }

        state.cashReport
            ?.let {
                report ->

                CashReportDialog(
                    report =
                        report,
                    strings =
                        managerStrings,
                    hardwareStrings =
                        hardwareStrings,
                    printMessage =
                        state.cashReportPrintMessage,
                    reportError =
                        state.cashReportError,
                    onPrint =
                        vm::printCashReport,
                    onDismiss =
                        vm::clearCashReport
                )
            }

        return
    }

    val open =
        session.status.equals(
            "open",
            ignoreCase = true
        )

    val pending =
        session.status.equals(
            "pending_approval",
            ignoreCase = true
        )

    val summary =
        state.cashSummary

    val opening =
        summary?.totals?.openingFloat
            ?: session.openingAmount
            ?: 0.0

    val expected =
        summary?.expectedCash
            ?: session.expectedAmount
            ?: 0.0

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    20.dp
                ),
        verticalArrangement =
            Arrangement.spacedBy(
                14.dp
            )
    ) {
        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Column(
                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {
                    Text(
                        s.title,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Black
                    )

                    Text(
                        "${s.session} #${session.id} • " +
                            (
                                session.registerName
                                    ?: state.cashRegisters
                                        .firstOrNull {
                                            it.id == session.registerId
                                        }
                                        ?.name
                                    ?: s.register
                            ),
                        color = CashMuted
                    )
                }

                AssistChip(
                    onClick = {
                        vm.refreshCashRegister()
                    },
                    label = {
                        Text(
                            if (pending) {
                                s.pendingApproval
                            } else {
                                s.sessionOpen
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (pending) {
                                Icons.Default.Lock
                            } else {
                                Icons.Default.PointOfSale
                            },
                            null,
                            modifier =
                                Modifier.size(
                                    18.dp
                                )
                        )
                    }
                )

                Spacer(
                    Modifier.width(
                        8.dp
                    )
                )

                IconButton(
                    onClick = {
                        vm.refreshCashRegister()
                    }
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        s.refresh
                    )
                }
            }
        }

        if (pending) {
            item {
                Surface(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(
                            18.dp
                        ),
                    color =
                        CashOrangeSoft
                ) {
                    Row(
                        modifier =
                            Modifier.padding(
                                18.dp
                            ),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            null,
                            tint = CashOrange
                        )

                        Spacer(
                            Modifier.width(
                                12.dp
                            )
                        )

                        Column {
                            Text(
                                s.pendingApproval,
                                fontWeight =
                                    FontWeight.Black
                            )

                            Text(
                                s.pendingHelp,
                                color = CashMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        if (
            state.hardwareMode ==
            be.cookit.pos.android.domain.HardwareMode.SIMULATED
        ) {
            item {
                HardwareSimulationBadge(
                    modifier =
                        Modifier.fillMaxWidth(),
                    strings =
                        hardwareStrings,
                    previewAvailable =
                        ! state.hardwareSimulationPreview
                            .isNullOrBlank(),
                    onPreview = {
                        hardwarePreviewOpen =
                            true
                    }
                )
            }
        }

        if (
            pending
            && state.policy.canApproveCashRegister
        ) {
            item {
                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                CashBlueSoft
                        ),
                    border =
                        BorderStroke(
                            1.dp,
                            CashLine
                        ),
                    shape =
                        RoundedCornerShape(
                            22.dp
                        )
                ) {
                    Column(
                        modifier =
                            Modifier.padding(
                                18.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                12.dp
                            )
                    ) {
                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                null
                            )

                            Spacer(
                                Modifier.width(
                                    10.dp
                                )
                            )

                            Column {
                                Text(
                                    managerStrings.managerTitle,
                                    fontWeight =
                                        FontWeight.Black,
                                    fontSize =
                                        18.sp
                                )

                                Text(
                                    managerStrings.managerHelp,
                                    color =
                                        CashMuted,
                                    fontSize =
                                        13.sp
                                )
                            }
                        }

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    10.dp
                                )
                        ) {
                            Button(
                                onClick = {
                                    approveConfirmOpen =
                                        true
                                },
                                enabled =
                                    ! state.cashManagerBusy,
                                modifier =
                                    Modifier.weight(
                                        1f
                                    )
                            ) {
                                Text(
                                    managerStrings.approve
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    rejectConfirmOpen =
                                        true
                                },
                                enabled =
                                    ! state.cashManagerBusy,
                                modifier =
                                    Modifier.weight(
                                        1f
                                    )
                            ) {
                                Text(
                                    managerStrings.reject
                                )
                            }
                        }

                        if (
                            state.cashManagerBusy
                        ) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier.size(
                                        22.dp
                                    )
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                CashMetric(
                    label = s.opening,
                    value = opening,
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )

                CashMetric(
                    label = s.expected,
                    value = expected,
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    emphasized = true
                )

                CashMetric(
                    label = s.cashIn,
                    value =
                        summary?.totals?.cashIn
                            ?: 0.0,
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )
            }
        }

        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                CashMetric(
                    label = s.cashOut,
                    value =
                        summary?.totals?.cashOut
                            ?: 0.0,
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )

                CashMetric(
                    label = s.safeDrop,
                    value =
                        summary?.totals?.safeDrops
                            ?: 0.0,
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )

                CashMetric(
                    label = s.cashSale,
                    value =
                        summary?.totals?.cashSales
                            ?: 0.0,
                    modifier =
                        Modifier.weight(
                            1f
                        )
                )
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            Color.White
                    ),
                border =
                    BorderStroke(
                        1.dp,
                        CashLine
                    ),
                shape =
                    RoundedCornerShape(
                        22.dp
                    )
            ) {
                Column(
                    modifier =
                        Modifier.padding(
                            18.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            12.dp
                        )
                ) {
                    Text(
                        s.subtitle,
                        fontWeight =
                            FontWeight.Black,
                        fontSize = 18.sp
                    )

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                10.dp
                            )
                    ) {
                        Button(
                            onClick = {
                                movementAction =
                                    MovementAction.CASH_IN
                            },
                            enabled =
                                open
                                && ! state.cashBusy,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text(
                                s.cashIn
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                movementAction =
                                    MovementAction.CASH_OUT
                            },
                            enabled =
                                open
                                && ! state.cashBusy,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text(
                                s.cashOut
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                movementAction =
                                    MovementAction.SAFE_DROP
                            },
                            enabled =
                                open
                                && ! state.cashBusy,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text(
                                s.safeDrop
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                10.dp
                            )
                    ) {
                        OutlinedButton(
                            onClick =
                                vm::openDrawer,
                            enabled =
                                ! state.cashBusy,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Icon(
                                Icons.Default.PointOfSale,
                                null
                            )

                            Spacer(
                                Modifier.width(
                                    8.dp
                                )
                            )

                            Text(
                                s.drawer
                            )
                        }

                        Button(
                            onClick = {
                                closeOpen = true
                            },
                            enabled =
                                open
                                && ! state.cashBusy,
                            modifier =
                                Modifier.weight(
                                    1f
                                )
                        ) {
                            Text(
                                s.close
                            )
                        }
                    }
                }
            }
        }

        if (
            state.policy.canViewCashRegisterReports
        ) {
            item {
                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                Color.White
                        ),
                    border =
                        BorderStroke(
                            1.dp,
                            CashLine
                        ),
                    shape =
                        RoundedCornerShape(
                            22.dp
                        )
                ) {
                    Column(
                        modifier =
                            Modifier.padding(
                                18.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                12.dp
                            )
                    ) {
                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ReceiptLong,
                                null
                            )

                            Spacer(
                                Modifier.width(
                                    10.dp
                                )
                            )

                            Column {
                                Text(
                                    managerStrings.reportsTitle,
                                    fontWeight =
                                        FontWeight.Black,
                                    fontSize =
                                        18.sp
                                )

                                Text(
                                    managerStrings.reportsHelp,
                                    color =
                                        CashMuted,
                                    fontSize =
                                        13.sp
                                )
                            }
                        }

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    10.dp
                                )
                        ) {
                            OutlinedButton(
                                onClick = {
                                    vm.loadCashReport(
                                        "x",
                                        session.id
                                    )
                                },
                                enabled =
                                    (
                                        open
                                        || pending
                                    )
                                    && ! state.cashReportBusy,
                                modifier =
                                    Modifier.weight(
                                        1f
                                    )
                            ) {
                                Text(
                                    managerStrings.xReport
                                )
                            }

                            Button(
                                onClick = {
                                    vm.loadCashReport(
                                        "z"
                                    )
                                },
                                enabled =
                                    state.lastClosedCashSession
                                        != null
                                    && ! state.cashReportBusy,
                                modifier =
                                    Modifier.weight(
                                        1f
                                    )
                            ) {
                                Text(
                                    managerStrings.zReport
                                )
                            }
                        }

                        if (
                            state.cashReportBusy
                        ) {
                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.size(
                                            20.dp
                                        )
                                )

                                Spacer(
                                    Modifier.width(
                                        10.dp
                                    )
                                )

                                Text(
                                    managerStrings.reportLoading,
                                    color =
                                        CashMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        state.cashReportError
            ?.let {
                error ->

                item {
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        color =
                            MaterialTheme
                                .colorScheme
                                .errorContainer,
                        shape =
                            RoundedCornerShape(
                                14.dp
                            )
                    ) {
                        Text(
                            cashReportErrorText(
                                error,
                                managerStrings
                            ),
                            modifier =
                                Modifier.padding(
                                    14.dp
                                ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onErrorContainer,
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }
            }

        state.cashActionMessage
            ?.let {
                marker ->
                item {
                    val message =
                        when (marker) {
                            "cash_session_opened" ->
                                s.opened

                            "cash_movement_saved" ->
                                s.movementSaved

                            "cash_closing_submitted" ->
                                s.closingSubmitted

                            "cash_closing_approved" ->
                                managerStrings.approved

                            "cash_closing_rejected" ->
                                managerStrings.rejected

                            else ->
                                marker
                        }

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        color =
                            CashGreenSoft,
                        shape =
                            RoundedCornerShape(
                                14.dp
                            )
                    ) {
                        Text(
                            message,
                            modifier =
                                Modifier.padding(
                                    14.dp
                                ),
                            color =
                                CashGreen,
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }
                }
            }

        state.cashActionError
            ?.let {
                marker ->
                item {
                    val message =
                        when (marker) {
                            "cash_register_missing" ->
                                s.registerMissing

                            "cash_session_not_open" ->
                                s.sessionNotOpen

                            "cash_amount_invalid" ->
                                s.invalidAmount

                            "cash_denominations_missing" ->
                                s.denominationsMissing

                            "cash_approval_forbidden" ->
                                managerStrings.approvalForbidden

                            "cash_session_not_pending" ->
                                managerStrings.sessionNotPending

                            "cash_report_forbidden" ->
                                managerStrings.reportForbidden

                            "cash_report_session_missing" ->
                                managerStrings.reportSessionMissing

                            "cash_device_identity_missing" ->
                                managerStrings.deviceMissing

                            else ->
                                marker
                        }

                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        color =
                            MaterialTheme
                                .colorScheme
                                .errorContainer,
                        shape =
                            RoundedCornerShape(
                                14.dp
                            )
                    ) {
                        Text(
                            message,
                            modifier =
                                Modifier.padding(
                                    14.dp
                                ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onErrorContainer
                        )
                    }
                }
            }

        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    s.movements,
                    modifier =
                        Modifier.weight(
                            1f
                        ),
                    fontSize = 20.sp,
                    fontWeight =
                        FontWeight.Black
                )

                Text(
                    "${state.cashMovements.size} ${s.transactions}",
                    color = CashMuted
                )
            }
        }

        if (
            state.cashMovements
                .isEmpty()
        ) {
            item {
                Text(
                    s.noMovements,
                    color =
                        CashMuted
                )
            }
        } else {
            items(
                state.cashMovements
                    .take(
                        20
                    ),
                key = {
                    it.id
                }
            ) {
                movement ->
                CashMovementRow(
                    movement = movement,
                    s = s
                )
            }
        }

        item {
            Spacer(
                Modifier.height(
                    16.dp
                )
            )
        }
    }

    movementAction
        ?.let {
            action ->
            MovementDialog(
                action = action,
                s = s,
                busy = state.cashBusy,
                onDismiss = {
                    movementAction = null
                },
                onConfirm = {
                    amount,
                    reason ->

                    when (action) {
                        MovementAction.CASH_IN ->
                            vm.cashIn(
                                amount,
                                reason
                            )

                        MovementAction.CASH_OUT ->
                            vm.cashOut(
                                amount,
                                reason
                            )

                        MovementAction.SAFE_DROP ->
                            vm.safeDrop(
                                amount,
                                reason
                            )
                    }

                    movementAction = null
                }
            )
        }

    if (closeOpen) {
        ClosingDialog(
            denominations =
                state.cashDenominations,
            expected =
                expected,
            s = s,
            busy =
                state.cashBusy,
            onDismiss = {
                closeOpen = false
            },
            onConfirm = {
                counts,
                note ->

                vm.submitCashClosing(
                    counts,
                    note
                )

                closeOpen = false
            }
        )
    }
    if (
        approveConfirmOpen
    ) {
        AlertDialog(
            onDismissRequest = {
                if (
                    ! state.cashManagerBusy
                ) {
                    approveConfirmOpen =
                        false
                }
            },
            title = {
                Text(
                    managerStrings.approveTitle,
                    fontWeight =
                        FontWeight.Black
                )
            },
            text = {
                Text(
                    managerStrings.approveMessage
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        approveConfirmOpen =
                            false

                        vm.approveCashClosing()
                    },
                    enabled =
                        ! state.cashManagerBusy
                ) {
                    Text(
                        managerStrings.approve
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        approveConfirmOpen =
                            false
                    },
                    enabled =
                        ! state.cashManagerBusy
                ) {
                    Text(
                        managerStrings.cancel
                    )
                }
            }
        )
    }

    if (
        rejectConfirmOpen
    ) {
        AlertDialog(
            onDismissRequest = {
                if (
                    ! state.cashManagerBusy
                ) {
                    rejectConfirmOpen =
                        false
                }
            },
            title = {
                Text(
                    managerStrings.rejectTitle,
                    fontWeight =
                        FontWeight.Black
                )
            },
            text = {
                Text(
                    managerStrings.rejectMessage
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        rejectConfirmOpen =
                            false

                        vm.rejectCashClosing()
                    },
                    enabled =
                        ! state.cashManagerBusy
                ) {
                    Text(
                        managerStrings.reject
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        rejectConfirmOpen =
                            false
                    },
                    enabled =
                        ! state.cashManagerBusy
                ) {
                    Text(
                        managerStrings.cancel
                    )
                }
            }
        )
    }

    state.cashReport
        ?.let {
            report ->

            CashReportDialog(
                report =
                    report,
                strings =
                    managerStrings,
                hardwareStrings =
                    hardwareStrings,
                printMessage =
                    state.cashReportPrintMessage,
                reportError =
                    state.cashReportError,
                onPrint =
                    vm::printCashReport,
                onDismiss =
                    vm::clearCashReport
            )
        }

}


@Composable
private fun HardwareSimulationBadge(
    modifier: Modifier = Modifier,
    strings: HardwareSimulationStrings,
    previewAvailable: Boolean,
    onPreview: () -> Unit
) {
    Surface(
        modifier =
            modifier,
        shape =
            RoundedCornerShape(
                16.dp
            ),
        color =
            CashOrangeSoft,
        border =
            BorderStroke(
                1.dp,
                CashOrange
            )
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(
                    10.dp
                )
        ) {
            Icon(
                Icons.Default.ReceiptLong,
                null,
                tint =
                    CashOrange
            )

            Column(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {
                Text(
                    strings.simulationBadge,
                    color =
                        CashOrange,
                    fontWeight =
                        FontWeight.Black,
                    fontSize =
                        12.sp
                )

                Text(
                    strings.simulationHelp,
                    color =
                        CashMuted,
                    fontSize =
                        11.sp
                )
            }

            if (
                previewAvailable
            ) {
                OutlinedButton(
                    onClick =
                        onPreview
                ) {
                    Text(
                        strings.viewPreview
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareSimulationPreviewDialog(
    preview: String,
    strings: HardwareSimulationStrings,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest =
            onDismiss
    ) {
        Surface(
            modifier =
                Modifier.fillMaxWidth(),
            shape =
                RoundedCornerShape(
                    24.dp
                ),
            color =
                CashCanvas
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            20.dp
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        14.dp
                    )
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        null,
                        tint =
                            CashOrange
                    )

                    Spacer(
                        Modifier.width(
                            10.dp
                        )
                    )

                    Column(
                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {
                        Text(
                            strings.previewTitle,
                            fontWeight =
                                FontWeight.Black,
                            fontSize =
                                21.sp
                        )

                        Text(
                            strings.simulationBadge,
                            color =
                                CashOrange,
                            fontWeight =
                                FontWeight.Bold,
                            fontSize =
                                11.sp
                        )
                    }
                }

                Surface(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(
                            14.dp
                        ),
                    color =
                        Color.White,
                    border =
                        BorderStroke(
                            1.dp,
                            CashLine
                        )
                ) {
                    Text(
                        text =
                            preview,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(
                                    rememberScrollState()
                                )
                                .padding(
                                    16.dp
                                ),
                        fontFamily =
                            androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize =
                            13.sp,
                        lineHeight =
                            18.sp
                    )
                }

                Button(
                    onClick =
                        onDismiss,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        strings.close
                    )
                }
            }
        }
    }
}

@Composable
private fun CashReportDialog(
    report: be.cookit.pos.android.domain.CashRegisterReport,
    strings: CashManagerStrings,
    hardwareStrings: HardwareSimulationStrings,
    printMessage: String?,
    reportError: String?,
    onPrint: () -> Unit,
    onDismiss: () -> Unit
) {
    fun money(
        value: Double
    ): String =
        String.format(
            Locale.FRANCE,
            "%.2f €",
            value
        )

    Dialog(
        onDismissRequest =
            onDismiss
    ) {
        Surface(
            modifier =
                Modifier.fillMaxWidth(),
            shape =
                RoundedCornerShape(
                    24.dp
                ),
            color =
                CashCanvas
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            rememberScrollState()
                        )
                        .padding(
                            20.dp
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ReceiptLong,
                        null
                    )

                    Spacer(
                        Modifier.width(
                            10.dp
                        )
                    )

                    Column(
                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {
                        Text(
                            if (
                                report.reportType.equals(
                                    "z",
                                    ignoreCase = true
                                )
                            ) {
                                strings.reportTitleZ
                            } else {
                                strings.reportTitleX
                            },
                            fontWeight =
                                FontWeight.Black,
                            fontSize =
                                24.sp
                        )

                        Text(
                            "${strings.session} #${report.sessionId}",
                            color =
                                CashMuted
                        )
                    }

                    IconButton(
                        onClick =
                            onDismiss
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            strings.close
                        )
                    }
                }

                HorizontalDivider()

                CashReportInfoRow(
                    label =
                        strings.register,
                    value =
                        report.registerName
                            ?: "—"
                )

                CashReportInfoRow(
                    label =
                        strings.cashier,
                    value =
                        report.cashierName
                            ?: "—"
                )

                report.openedAt
                    ?.let {
                        CashReportInfoRow(
                            label =
                                strings.opened,
                            value =
                                it
                        )
                    }

                report.closedAt
                    ?.let {
                        CashReportInfoRow(
                            label =
                                strings.closed,
                            value =
                                it
                        )
                    }

                report.generatedAt
                    ?.let {
                        CashReportInfoRow(
                            label =
                                strings.generated,
                            value =
                                it
                        )
                    }

                HorizontalDivider()

                CashReportMoneyRow(
                    strings.opening,
                    report.openingFloat,
                    ::money
                )

                CashReportMoneyRow(
                    strings.cashSales,
                    report.cashSales,
                    ::money
                )

                CashReportMoneyRow(
                    strings.totalPayments,
                    report.totalPayments,
                    ::money
                )

                CashReportMoneyRow(
                    strings.changeGiven,
                    report.changeGiven,
                    ::money
                )

                CashReportMoneyRow(
                    strings.cashIn,
                    report.cashIn,
                    ::money
                )

                CashReportMoneyRow(
                    strings.cashOut,
                    report.cashOut,
                    ::money
                )

                CashReportMoneyRow(
                    strings.safeDrop,
                    report.safeDrops,
                    ::money
                )

                CashReportMoneyRow(
                    strings.refunds,
                    report.refunds,
                    ::money
                )

                Surface(
                    modifier =
                        Modifier.fillMaxWidth(),
                    shape =
                        RoundedCornerShape(
                            16.dp
                        ),
                    color =
                        CashOrangeSoft
                ) {
                    Row(
                        modifier =
                            Modifier.padding(
                                14.dp
                            )
                            .fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.SpaceBetween
                    ) {
                        Text(
                            strings.expected,
                            fontWeight =
                                FontWeight.Black
                        )

                        Text(
                            money(
                                report.expectedCash
                            ),
                            fontWeight =
                                FontWeight.Black
                        )
                    }
                }

                report.physicalCashCounted
                    ?.let {
                        CashReportMoneyRow(
                            strings.physical,
                            it,
                            ::money
                        )
                    }

                report.countedCash
                    ?.let {
                        CashReportMoneyRow(
                            strings.counted,
                            it,
                            ::money
                        )
                    }

                report.discrepancy
                    ?.let {
                        CashReportMoneyRow(
                            strings.discrepancy,
                            it,
                            ::money
                        )
                    }

                if (
                    report.paymentMethodTotals
                        .isNotEmpty()
                ) {
                    HorizontalDivider()

                    Text(
                        strings.paymentMethods,
                        fontWeight =
                            FontWeight.Black,
                        fontSize =
                            17.sp
                    )

                    report.paymentMethodTotals
                        .toSortedMap()
                        .forEach {
                            (method, amount) ->

                            CashReportInfoRow(
                                label =
                                    method,
                                value =
                                    money(
                                        amount
                                    )
                            )
                        }
                }

                if (
                    report.denominations
                        .isNotEmpty()
                ) {
                    HorizontalDivider()

                    Text(
                        strings.denominations,
                        fontWeight =
                            FontWeight.Black,
                        fontSize =
                            17.sp
                    )

                    report.denominations
                        .forEach {
                            denomination ->

                            CashReportInfoRow(
                                label =
                                    "${denomination.count} × " +
                                    money(
                                        denomination.value
                                    ),
                                value =
                                    money(
                                        denomination.subtotal
                                    )
                            )
                        }
                }

                reportError
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        error ->

                        Text(
                            cashReportErrorText(
                                error,
                                strings
                            ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }

                printMessage
                    ?.let {
                        marker ->

                        Text(
                            when (
                                marker
                            ) {
                                "cash_report_printing" ->
                                    strings.printing

                                "cash_report_printed" ->
                                    strings.printed

                                "cash_report_printed_simulated" ->
                                    hardwareStrings.virtualPrintReady

                                "cash_report_print_failed",
                                "cash_report_print_missing",
                                "cash_report_print_forbidden" ->
                                    strings.printFailed

                                else ->
                                    marker
                            },
                            color =
                                when (
                                    marker
                                ) {
                                    "cash_report_printed",
                                    "cash_report_printed_simulated" ->
                                        CashGreen

                                    "cash_report_printing" ->
                                        CashMuted

                                    else ->
                                        MaterialTheme
                                            .colorScheme
                                            .error
                                },
                            fontWeight =
                                FontWeight.SemiBold
                        )
                    }

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    Button(
                        onClick =
                            onPrint,
                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            null
                        )

                        Spacer(
                            Modifier.width(
                                8.dp
                            )
                        )

                        Text(
                            strings.print
                        )
                    }

                    OutlinedButton(
                        onClick =
                            onDismiss,
                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {
                        Text(
                            strings.close
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CashReportInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Text(
            label,
            color =
                CashMuted,
            modifier =
                Modifier.weight(
                    1f
                )
        )

        Spacer(
            Modifier.width(
                12.dp
            )
        )

        Text(
            value,
            fontWeight =
                FontWeight.SemiBold
        )
    }
}

@Composable
private fun CashReportMoneyRow(
    label: String,
    value: Double,
    formatter: (Double) -> String
) {
    CashReportInfoRow(
        label =
            label,
        value =
            formatter(
                value
            )
    )
}


@Composable
private fun CashRegisterOpening(
    state: PosUiState,
    vm: CookitPosViewModel,
    s: CashRegisterStrings
) {
    var selectedRegisterId by
        remember(
            state.cashRegisters
        ) {
            mutableStateOf(
                state.cashRegisters
                    .firstOrNull {
                        it.isActive
                    }
                    ?.id
            )
        }

    var manualAmount by
        remember {
            mutableStateOf(
                ""
            )
        }

    var quantities by
        remember(
            state.cashDenominations
        ) {
            mutableStateOf(
                state.cashDenominations
                    .associate {
                        it.label to 0
                    }
            )
        }

    val denominationTotal =
        state.cashDenominations
            .sumOf {
                denomination ->
                denomination.value *
                    (
                        quantities[
                            denomination.label
                        ] ?: 0
                    )
            }

    val manual =
        manualAmount
            .replace(
                ",",
                "."
            )
            .toDoubleOrNull()

    val openingAmount =
        if (
            state.cashDenominations
                .isEmpty()
        ) {
            manual
                ?: 0.0
        } else {
            denominationTotal
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    20.dp
                ),
        verticalArrangement =
            Arrangement.spacedBy(
                16.dp
            )
    ) {
        Text(
            s.openingTitle,
            fontSize = 30.sp,
            fontWeight =
                FontWeight.Black
        )

        Text(
            s.openingHelp,
            color =
                CashMuted
        )

        if (
            state.cashRegisters
                .isEmpty()
        ) {
            Surface(
                modifier =
                    Modifier.fillMaxWidth(),
                color =
                    MaterialTheme
                        .colorScheme
                        .errorContainer,
                shape =
                    RoundedCornerShape(
                        16.dp
                    )
            ) {
                Text(
                    s.registerMissing,
                    modifier =
                        Modifier.padding(
                            16.dp
                        ),
                    color =
                        MaterialTheme
                            .colorScheme
                            .onErrorContainer
                )
            }
        } else {
            Text(
                s.register,
                fontWeight =
                    FontWeight.Bold
            )

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(
                        8.dp
                    )
            ) {
                state.cashRegisters
                    .filter {
                        it.isActive
                    }
                    .forEach {
                        register ->
                        FilterChip(
                            selected =
                                selectedRegisterId
                                    == register.id,
                            onClick = {
                                selectedRegisterId =
                                    register.id
                            },
                            label = {
                                Text(
                                    register.name
                                )
                            }
                        )
                    }
            }
        }

        Card(
            modifier =
                Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        Color.White
                ),
            border =
                BorderStroke(
                    1.dp,
                    CashLine
                ),
            shape =
                RoundedCornerShape(
                    22.dp
                )
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        18.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Text(
                        s.openingFloat,
                        modifier =
                            Modifier.weight(
                                1f
                            ),
                        fontWeight =
                            FontWeight.Black
                    )

                    Text(
                        money(
                            openingAmount
                        ),
                        fontSize =
                            24.sp,
                        fontWeight =
                            FontWeight.Black,
                        color =
                            CashOrange
                    )
                }

                if (
                    state.cashDenominations
                        .isEmpty()
                ) {
                    OutlinedTextField(
                        value =
                            manualAmount,
                        onValueChange = {
                            manualAmount =
                                sanitizeAmount(
                                    it
                                )
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                s.openingFloat
                            )
                        },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    KeyboardType.Decimal
                            )
                    )
                } else {
                    state.cashDenominations
                        .forEach {
                            denomination ->
                            val quantity =
                                quantities[
                                    denomination.label
                                ] ?: 0

                            DenominationRow(
                                denomination =
                                    denomination,
                                quantity =
                                    quantity,
                                onQuantity = {
                                    next ->
                                    quantities =
                                        quantities
                                            .toMutableMap()
                                            .also {
                                                it[
                                                    denomination.label
                                                ] =
                                                    next
                                            }
                                }
                            )
                        }
                }
            }
        }

        Button(
            onClick = {
                vm.openCashSession(
                    openingAmount =
                        openingAmount,
                    registerId =
                        selectedRegisterId
                )
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        56.dp
                    ),
            enabled =
                ! state.cashBusy
                && selectedRegisterId != null
        ) {
            if (
                state.cashBusy
            ) {
                CircularProgressIndicator(
                    modifier =
                        Modifier.size(
                            19.dp
                        ),
                    strokeWidth =
                        2.dp,
                    color =
                        Color.White
                )
            } else {
                Text(
                    s.start,
                    fontWeight =
                        FontWeight.Black
                )
            }
        }

        state.cashActionError
            ?.let {
                marker ->
                Text(
                    when (marker) {
                        "cash_register_missing" ->
                            s.registerMissing

                        else ->
                            marker
                    },
                    color =
                        MaterialTheme
                            .colorScheme
                            .error
                )
            }
    }
}

@Composable
private fun CashMetric(
    label: String,
    value: Double,
    modifier: Modifier,
    emphasized: Boolean = false
) {
    Card(
        modifier =
            modifier,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (emphasized) {
                        CashOrangeSoft
                    } else {
                        Color.White
                    }
            ),
        border =
            BorderStroke(
                1.dp,
                if (emphasized) {
                    CashOrange.copy(
                        alpha = 0.25f
                    )
                } else {
                    CashLine
                }
            ),
        shape =
            RoundedCornerShape(
                18.dp
            )
    ) {
        Column(
            modifier =
                Modifier.padding(
                    16.dp
                )
        ) {
            Text(
                label,
                color =
                    CashMuted,
                fontSize =
                    12.sp,
                fontWeight =
                    FontWeight.SemiBold
            )

            Spacer(
                Modifier.height(
                    6.dp
                )
            )

            Text(
                money(
                    value
                ),
                fontSize =
                    22.sp,
                fontWeight =
                    FontWeight.Black,
                color =
                    if (emphasized) {
                        CashOrange
                    } else {
                        Color.Unspecified
                    }
            )
        }
    }
}

@Composable
private fun CashMovementRow(
    movement: CashMovement,
    s: CashRegisterStrings
) {
    val positive =
        movement.type
            .lowercase()
            .let {
                it == "cash_in"
                || it == "cash_sale"
                || it == "opening_float"
            }

    val label =
        when (
            movement.type
                .lowercase()
        ) {
            "cash_in" ->
                s.cashIn

            "cash_out" ->
                s.cashOut

            "safe_drop" ->
                s.safeDrop

            "cash_sale" ->
                s.cashSale

            "refund" ->
                s.refund

            "opening_float" ->
                s.opening

            else ->
                movement.type
        }

    Card(
        modifier =
            Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.White
            ),
        border =
            BorderStroke(
                1.dp,
                CashLine
            ),
        shape =
            RoundedCornerShape(
                16.dp
            )
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        14.dp
                    ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Surface(
                color =
                    if (positive) {
                        CashGreenSoft
                    } else {
                        CashBlueSoft
                    },
                shape =
                    RoundedCornerShape(
                        12.dp
                    )
            ) {
                Icon(
                    Icons.Default.Payments,
                    null,
                    modifier =
                        Modifier.padding(
                            10.dp
                        ),
                    tint =
                        if (positive) {
                            CashGreen
                        } else {
                            CashOrange
                        }
                )
            }

            Spacer(
                Modifier.width(
                    12.dp
                )
            )

            Column(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {
                Text(
                    label,
                    fontWeight =
                        FontWeight.Bold
                )

                movement.reason
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        Text(
                            it,
                            color =
                                CashMuted,
                            fontSize =
                                12.sp
                        )
                    }
            }

            Text(
                (
                    if (positive) {
                        "+ "
                    } else {
                        "− "
                    }
                ) +
                    money(
                        movement.amount
                    ),
                fontWeight =
                    FontWeight.Black,
                color =
                    if (positive) {
                        CashGreen
                    } else {
                        CashOrange
                    }
            )
        }
    }
}

@Composable
private fun MovementDialog(
    action: MovementAction,
    s: CashRegisterStrings,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        Double,
        String?
    ) -> Unit
) {
    var amountText by
        remember {
            mutableStateOf(
                ""
            )
        }

    var reason by
        remember {
            mutableStateOf(
                ""
            )
        }

    val amount =
        amountText
            .replace(
                ",",
                "."
            )
            .toDoubleOrNull()

    val title =
        when (action) {
            MovementAction.CASH_IN ->
                s.cashIn

            MovementAction.CASH_OUT ->
                s.cashOut

            MovementAction.SAFE_DROP ->
                s.safeDrop
        }

    AlertDialog(
        onDismissRequest =
            onDismiss,
        title = {
            Text(
                title
            )
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {
                OutlinedTextField(
                    value =
                        amountText,
                    onValueChange = {
                        amountText =
                            sanitizeAmount(
                                it
                            )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            s.amount
                        )
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.Decimal
                        )
                )

                OutlinedTextField(
                    value =
                        reason,
                    onValueChange = {
                        reason = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            s.reason
                        )
                    }
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick =
                    onDismiss
            ) {
                Text(
                    s.cancel
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        amount
                            ?: 0.0,
                        reason
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                    )
                },
                enabled =
                    ! busy
                    && (
                        amount
                            ?: 0.0
                    ) > 0.0
            ) {
                Text(
                    s.confirm
                )
            }
        }
    )
}

@Composable
private fun ClosingDialog(
    denominations: List<CashDenomination>,
    expected: Double,
    s: CashRegisterStrings,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        Map<Long, Int>,
        String?
    ) -> Unit
) {
    var quantities by
        remember(
            denominations
        ) {
            mutableStateOf(
                denominations
                    .associate {
                        it.label to 0
                    }
            )
        }

    var note by
        remember {
            mutableStateOf(
                ""
            )
        }

    val counted =
        denominations
            .sumOf {
                denomination ->
                denomination.value *
                    (
                        quantities[
                            denomination.label
                        ] ?: 0
                    )
            }

    val difference =
        counted - expected

    val validDenominations =
        denominations
            .filter {
                it.id != null
            }

    Dialog(
        onDismissRequest =
            onDismiss
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        16.dp
                    ),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        Color.White
                ),
            shape =
                RoundedCornerShape(
                    26.dp
                )
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(
                            20.dp
                        )
                        .verticalScroll(
                            rememberScrollState()
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        14.dp
                    )
            ) {
                Text(
                    s.closeTitle,
                    fontSize =
                        24.sp,
                    fontWeight =
                        FontWeight.Black
                )

                Text(
                    s.closeHelp,
                    color =
                        CashMuted
                )

                if (
                    validDenominations
                        .isEmpty()
                ) {
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        color =
                            MaterialTheme
                                .colorScheme
                                .errorContainer,
                        shape =
                            RoundedCornerShape(
                                14.dp
                            )
                    ) {
                        Text(
                            s.denominationsMissing,
                            modifier =
                                Modifier.padding(
                                    14.dp
                                ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onErrorContainer
                        )
                    }
                } else {
                    validDenominations
                        .forEach {
                            denomination ->
                            val quantity =
                                quantities[
                                    denomination.label
                                ] ?: 0

                            DenominationRow(
                                denomination =
                                    denomination,
                                quantity =
                                    quantity,
                                onQuantity = {
                                    next ->
                                    quantities =
                                        quantities
                                            .toMutableMap()
                                            .also {
                                                it[
                                                    denomination.label
                                                ] =
                                                    next
                                            }
                                }
                            )
                        }
                }

                HorizontalDivider(
                    color =
                        CashLine
                )

                CashClosingTotal(
                    label =
                        s.expected,
                    value =
                        expected
                )

                CashClosingTotal(
                    label =
                        s.counted,
                    value =
                        counted
                )

                CashClosingTotal(
                    label =
                        s.difference,
                    value =
                        difference,
                    highlight =
                        true
                )

                OutlinedTextField(
                    value =
                        note,
                    onValueChange = {
                        note = it
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            s.closingNote
                        )
                    }
                )

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            10.dp
                        )
                ) {
                    OutlinedButton(
                        onClick =
                            onDismiss,
                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {
                        Text(
                            s.cancel
                        )
                    }

                    Button(
                        onClick = {
                            val counts =
                                validDenominations
                                    .associate {
                                        denomination ->
                                        denomination.id!! to
                                            (
                                                quantities[
                                                    denomination.label
                                                ] ?: 0
                                            )
                                    }

                            onConfirm(
                                counts,
                                note
                                    .trim()
                                    .takeIf {
                                        it.isNotBlank()
                                    }
                            )
                        },
                        enabled =
                            ! busy
                            && validDenominations
                                .isNotEmpty(),
                        modifier =
                            Modifier.weight(
                                1f
                            )
                    ) {
                        Text(
                            s.submitClose
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DenominationRow(
    denomination: CashDenomination,
    quantity: Int,
    onQuantity: (Int) -> Unit
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Column(
            modifier =
                Modifier.weight(
                    1f
                )
        ) {
            Text(
                denomination.label,
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                money(
                    denomination.value
                ),
                color =
                    CashMuted,
                fontSize =
                    12.sp
            )
        }

        IconButton(
            onClick = {
                onQuantity(
                    (
                        quantity - 1
                    ).coerceAtLeast(
                        0
                    )
                )
            }
        ) {
            Icon(
                Icons.Default.RemoveCircleOutline,
                null
            )
        }

        Text(
            quantity.toString(),
            modifier =
                Modifier.width(
                    34.dp
                ),
            fontWeight =
                FontWeight.Black
        )

        IconButton(
            onClick = {
                onQuantity(
                    quantity + 1
                )
            }
        ) {
            Icon(
                Icons.Default.AddCircleOutline,
                null,
                tint =
                    CashOrange
            )
        }

        Text(
            money(
                denomination.value *
                    quantity
            ),
            modifier =
                Modifier.width(
                    96.dp
                ),
            fontWeight =
                FontWeight.Black
        )
    }
}

@Composable
private fun CashClosingTotal(
    label: String,
    value: Double,
    highlight: Boolean = false
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            modifier =
                Modifier.weight(
                    1f
                ),
            color =
                CashMuted
        )

        Text(
            money(
                value
            ),
            fontWeight =
                FontWeight.Black,
            color =
                if (highlight) {
                    if (
                        kotlin.math.abs(
                            value
                        ) < 0.005
                    ) {
                        CashGreen
                    } else {
                        CashOrange
                    }
                } else {
                    Color.Unspecified
                }
        )
    }
}

private fun money(
    value: Double
): String =
    String.format(
        Locale.FRANCE,
        "%.2f €",
        value
    )

private fun sanitizeAmount(
    value: String
): String {
    val normalized =
        value
            .replace(
                ",",
                "."
            )
            .filter {
                it.isDigit()
                || it == '.'
            }

    val firstDot =
        normalized.indexOf(
            '.'
        )

    if (firstDot < 0) {
        return normalized
    }

    return normalized
        .substring(
            0,
            firstDot + 1
        ) +
        normalized
            .substring(
                firstDot + 1
            )
            .replace(
                ".",
                ""
            )
            .take(
                2
            )
}
