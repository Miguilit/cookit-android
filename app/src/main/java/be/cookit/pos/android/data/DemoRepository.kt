package be.cookit.pos.android.data

import be.cookit.pos.android.domain.*

object DemoRepository {
    val user = UserSession(
        id = 1,
        name = "Amina",
        role = PosRole.CASHIER,
        restaurant = "Restaurant Yémenite",
        branch = "Branche St Gilles"
    )

    val policy = NativePolicy(
        profile = PosRole.CASHIER,
        cashSessionRequired = true,
        canManageSettings = false,
        canManagePrinters = false,
        canUsePos = true,
        canViewKds = true,
        canViewDelivery = true
    )

    val categories = listOf(
        Category(1, "Populaires", "🔥"),
        Category(2, "Entrées", "🥗"),
        Category(3, "Plats", "🍛"),
        Category(4, "Grillades", "🔥"),
        Category(5, "Boissons", "🥤"),
        Category(6, "Desserts", "🍰")
    )

    val products = listOf(
        Product(1, 1, "Mandi Poulet", "Riz parfumé, poulet rôti", 16.90, "🍗"),
        Product(2, 1, "Mandi Agneau", "Riz basmati, agneau tendre", 21.50, "🍖"),
        Product(3, 3, "Fahsa", "Bœuf mijoté, fenugrec", 17.90, "🥘"),
        Product(4, 4, "Mix Grill", "Poulet, kefta, agneau", 22.00, "🍢"),
        Product(5, 2, "Samboussa", "3 pièces, sauce maison", 6.50, "🥟"),
        Product(6, 2, "Salade Fattoush", "Crudités, pain grillé", 7.90, "🥗"),
        Product(7, 5, "Thé Adeni", "Thé noir, lait et épices", 3.50, "🫖"),
        Product(8, 5, "Jus Mangue", "Pressé, 33 cl", 4.20, "🥭"),
        Product(9, 6, "Basboussa", "Semoule, sirop, amandes", 5.90, "🍰"),
        Product(10, 3, "Saltah", "Ragoût yéménite traditionnel", 15.50, "🍲"),
        Product(11, 4, "Kefta Grill", "Brochettes, légumes grillés", 18.20, "🍢"),
        Product(12, 3, "Kabsa Poulet", "Riz épicé, poulet", 15.90, "🍚")
    )

    val orders = listOf(
        PosOrder(101, "#1248", "QR Table", OrderType.DINE_IN, "Nouveau", 42.80, "Table 7", "T7", 1, true, "placed"),
        PosOrder(102, "#1247", "Site web", OrderType.TAKEAWAY, "Confirmé", 26.40, "Sofia M.", null, 4, true, "confirmed"),
        PosOrder(103, "#1246", "POS", OrderType.DINE_IN, "En cuisine", 58.20, "Table 3", "T3", 7, false, "preparing"),
        PosOrder(104, "#1245", "Delivery", OrderType.DELIVERY, "Prêt", 31.90, "Karim B.", null, 12, false, "food_ready")
    )

    val denominations = listOf(
        CashDenomination("0,10 €", 0.10),
        CashDenomination("0,20 €", 0.20),
        CashDenomination("0,50 €", 0.50),
        CashDenomination("1 €", 1.0),
        CashDenomination("2 €", 2.0),
        CashDenomination("5 €", 5.0),
        CashDenomination("10 €", 10.0),
        CashDenomination("20 €", 20.0),
        CashDenomination("50 €", 50.0)
    )
}
