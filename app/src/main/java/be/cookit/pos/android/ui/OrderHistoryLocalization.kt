package be.cookit.pos.android.ui

import be.cookit.pos.android.domain.AppLanguage

data class OrderHistoryStrings(
    val details: String,
    val orderDetails: String,
    val items: String,
    val payments: String,
    val fiscalReceipt: String,
    val reprint: String,
    val reprintSummary: String,
    val reprintAllSplits: String,
    val reprintSplit: String,
    val printing: String,
    val printed: String,
    val printFailed: String,
    val noPayments: String,
    val paidAmount: String,
    val discounts: String,
    val subtotal: String,
    val tip: String,
    val vat: String,
    val receiptNumber: String,
    val close: String
)

fun orderHistoryStrings(language: AppLanguage): OrderHistoryStrings = when (language) {
    AppLanguage.FR -> OrderHistoryStrings("Détails", "Détail de la commande", "Articles", "Paiements", "Référence fiscale", "Réimprimer", "Réimprimer le ticket", "Réimprimer tous les tickets séparés", "Réimprimer cette part", "Impression…", "Ticket réimprimé", "Échec de l’impression", "Aucun paiement", "Montant payé", "Réductions", "Sous-total", "Pourboire", "TVA", "N° reçu", "Fermer")
    AppLanguage.NL -> OrderHistoryStrings("Details", "Besteldetails", "Artikelen", "Betalingen", "Fiscale referentie", "Opnieuw afdrukken", "Ticket opnieuw afdrukken", "Alle gesplitste tickets opnieuw afdrukken", "Dit deel opnieuw afdrukken", "Afdrukken…", "Ticket opnieuw afgedrukt", "Afdrukken mislukt", "Geen betaling", "Betaald bedrag", "Kortingen", "Subtotaal", "Fooi", "Btw", "Ontvangstnr.", "Sluiten")
    AppLanguage.EN -> OrderHistoryStrings("Details", "Order details", "Items", "Payments", "Fiscal reference", "Reprint", "Reprint receipt", "Reprint all split receipts", "Reprint this split", "Printing…", "Receipt reprinted", "Printing failed", "No payments", "Amount paid", "Discounts", "Subtotal", "Tip", "VAT", "Receipt no.", "Close")
    AppLanguage.DE -> OrderHistoryStrings("Details", "Bestelldetails", "Artikel", "Zahlungen", "Fiskalreferenz", "Nachdrucken", "Beleg nachdrucken", "Alle Split-Belege nachdrucken", "Diesen Teil nachdrucken", "Druck läuft…", "Beleg nachgedruckt", "Druck fehlgeschlagen", "Keine Zahlung", "Bezahlter Betrag", "Rabatte", "Zwischensumme", "Trinkgeld", "MwSt.", "Belegnr.", "Schließen")
}
