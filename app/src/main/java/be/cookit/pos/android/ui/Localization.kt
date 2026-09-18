package be.cookit.pos.android.ui

import be.cookit.pos.android.domain.AppLanguage

data class UiStrings(
    val home: String,
    val pos: String,
    val orders: String,
    val kitchen: String,
    val delivery: String,
    val cash: String,
    val settings: String,
    val online: String,
    val offline: String,
    val newOrder: String,
    val dineIn: String,
    val takeaway: String,
    val deliveryType: String,
    val cart: String,
    val subtotal: String,
    val vatIncluded: String,
    val total: String,
    val checkout: String,
    val emptyCart: String,
    val chooseTable: String,
    val cashOpening: String,
    val cashOpeningHelp: String,
    val cashFund: String,
    val drawer: String,
    val startService: String,
    val activeSession: String,
    val refresh: String,
    val logout: String,
    val synchronization: String,
    val printers: String,
    val account: String,
    val permissions: String,
    val language: String,
    val printerHost: String,
    val printerPort: String,
    val testPrinter: String,
    val printerReady: String,
    val printerFailed: String,
    val orderPaid: String,
    val openCashFirst: String,
    val settingsHelp: String
)

fun strings(language: AppLanguage): UiStrings = when (language) {
    AppLanguage.FR -> UiStrings(
        home="Accueil", pos="Caisse", orders="Commandes", kitchen="Cuisine", delivery="Livraison",
        cash="Fond de caisse", settings="Réglages", online="En ligne", offline="Hors ligne",
        newOrder="Nouvelle commande", dineIn="Sur place", takeaway="À emporter", deliveryType="Livraison",
        cart="Panier", subtotal="Sous-total", vatIncluded="TVA incluse", total="Total", checkout="Encaisser",
        emptyCart="Panier vide", chooseTable="Table", cashOpening="Ouverture de caisse",
        cashOpeningHelp="Comptage du fond de caisse avant le service.", cashFund="FOND DE CAISSE",
        drawer="Ouvrir le tiroir", startService="Démarrer le service", activeSession="Session de caisse active",
        refresh="Rafraîchir", logout="Déconnexion", synchronization="Synchronisation", printers="Imprimantes",
        account="Compte", permissions="Permissions", language="Langue", printerHost="IP imprimante",
        printerPort="Port", testPrinter="Tester ESC/POS", printerReady="Imprimante joignable",
        printerFailed="Imprimante non joignable", orderPaid="Commande créée et encaissée",
        openCashFirst="Ouvrez d’abord une session de caisse.", settingsHelp="Session Cookit connectée • synchro toutes les 2 s"
    )
    AppLanguage.NL -> UiStrings(
        home="Start", pos="Kassa", orders="Bestellingen", kitchen="Keuken", delivery="Levering",
        cash="Kasgeld", settings="Instellingen", online="Online", offline="Offline",
        newOrder="Nieuwe bestelling", dineIn="Ter plaatse", takeaway="Afhalen", deliveryType="Levering",
        cart="Winkelmand", subtotal="Subtotaal", vatIncluded="Btw inbegrepen", total="Totaal", checkout="Afrekenen",
        emptyCart="Winkelmand leeg", chooseTable="Tafel", cashOpening="Kassa openen",
        cashOpeningHelp="Tel het startbedrag voor de dienst.", cashFund="KASGELD",
        drawer="Kassalade openen", startService="Dienst starten", activeSession="Actieve kassasessie",
        refresh="Vernieuwen", logout="Afmelden", synchronization="Synchronisatie", printers="Printers",
        account="Account", permissions="Rechten", language="Taal", printerHost="Printer-IP",
        printerPort="Poort", testPrinter="ESC/POS testen", printerReady="Printer bereikbaar",
        printerFailed="Printer niet bereikbaar", orderPaid="Bestelling aangemaakt en betaald",
        openCashFirst="Open eerst een kassasessie.", settingsHelp="Cookit-sessie verbonden • sync elke 2 s"
    )
    AppLanguage.EN -> UiStrings(
        home="Home", pos="POS", orders="Orders", kitchen="Kitchen", delivery="Delivery",
        cash="Cash drawer", settings="Settings", online="Online", offline="Offline",
        newOrder="New order", dineIn="Dine in", takeaway="Takeaway", deliveryType="Delivery",
        cart="Cart", subtotal="Subtotal", vatIncluded="VAT included", total="Total", checkout="Charge",
        emptyCart="Cart is empty", chooseTable="Table", cashOpening="Open cash register",
        cashOpeningHelp="Count the opening float before service.", cashFund="OPENING FLOAT",
        drawer="Open drawer", startService="Start service", activeSession="Active cash session",
        refresh="Refresh", logout="Sign out", synchronization="Synchronization", printers="Printers",
        account="Account", permissions="Permissions", language="Language", printerHost="Printer IP",
        printerPort="Port", testPrinter="Test ESC/POS", printerReady="Printer reachable",
        printerFailed="Printer unreachable", orderPaid="Order created and paid",
        openCashFirst="Open a cash session first.", settingsHelp="Cookit session connected • sync every 2 s"
    )
    AppLanguage.DE -> UiStrings(
        home="Start", pos="Kasse", orders="Bestellungen", kitchen="Küche", delivery="Lieferung",
        cash="Kassenbestand", settings="Einstellungen", online="Online", offline="Offline",
        newOrder="Neue Bestellung", dineIn="Vor Ort", takeaway="Abholung", deliveryType="Lieferung",
        cart="Warenkorb", subtotal="Zwischensumme", vatIncluded="MwSt. inkl.", total="Gesamt", checkout="Kassieren",
        emptyCart="Warenkorb leer", chooseTable="Tisch", cashOpening="Kasse öffnen",
        cashOpeningHelp="Anfangsbestand vor dem Service zählen.", cashFund="KASSENBESTAND",
        drawer="Kassenschublade öffnen", startService="Service starten", activeSession="Aktive Kassensitzung",
        refresh="Aktualisieren", logout="Abmelden", synchronization="Synchronisierung", printers="Drucker",
        account="Konto", permissions="Berechtigungen", language="Sprache", printerHost="Drucker-IP",
        printerPort="Port", testPrinter="ESC/POS testen", printerReady="Drucker erreichbar",
        printerFailed="Drucker nicht erreichbar", orderPaid="Bestellung erstellt und bezahlt",
        openCashFirst="Öffnen Sie zuerst eine Kassensitzung.", settingsHelp="Cookit verbunden • Sync alle 2 s"
    )
}
