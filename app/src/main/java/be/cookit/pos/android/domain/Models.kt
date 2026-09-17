package be.cookit.pos.android.domain

enum class PosRole { ADMINISTRATOR, MANAGER, CASHIER, WAITER, KITCHEN, DELIVERY }

enum class OrderType { DINE_IN, TAKEAWAY, DELIVERY }

data class UserSession(
    val id: Long,
    val name: String,
    val role: PosRole,
    val restaurant: String,
    val branch: String
)

data class Category(val id: Long, val name: String, val emoji: String)

data class Product(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val description: String,
    val price: Double,
    val emoji: String,
    val available: Boolean = true
)

data class CartLine(
    val product: Product,
    val quantity: Int
) {
    val total: Double get() = product.price * quantity
}

data class PosOrder(
    val id: Long,
    val code: String,
    val channel: String,
    val type: OrderType,
    val status: String,
    val total: Double,
    val customer: String,
    val table: String? = null,
    val minutesAgo: Int = 0,
    val unread: Boolean = false
)

data class CashDenomination(
    val label: String,
    val value: Double,
    val quantity: Int = 0
)

data class NativePolicy(
    val profile: PosRole,
    val cashSessionRequired: Boolean,
    val canManageSettings: Boolean,
    val canManagePrinters: Boolean,
    val canUsePos: Boolean,
    val canViewKds: Boolean,
    val canViewDelivery: Boolean
)
