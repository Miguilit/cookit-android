package be.cookit.pos.android.domain

enum class PosRole { ADMINISTRATOR, MANAGER, CASHIER, WAITER, KITCHEN, DELIVERY }

enum class OrderType { DINE_IN, TAKEAWAY, DELIVERY }

enum class PosPaymentMethod(val apiValue: String) {
    CASH("cash"),
    CARD_TERMINAL("card")
}

enum class AppLanguage(val code: String, val label: String) {
    FR("fr", "Français"),
    NL("nl", "Nederlands"),
    EN("en", "English"),
    DE("de", "Deutsch");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: FR
    }
}

enum class PrinterProviderType { ESC_POS, STAR }

enum class StarInterfaceType { LAN, BLUETOOTH, BLUETOOTH_LE, USB }

data class UserSession(
    val id: Long,
    val name: String,
    val role: PosRole,
    val restaurant: String,
    val branch: String,
    val restaurantLogoUrl: String? = null
)

data class Category(val id: Long, val name: String, val emoji: String)

data class Product(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val description: String,
    val price: Double,
    val emoji: String,
    val available: Boolean = true,
    val imageUrl: String? = null
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
    val unread: Boolean = false,
    val remoteStatus: String = "placed",
    val settlementStatus: String = "unknown",
    val createdAtEpochMs: Long? = null
)

data class RemoteOrderLine(
    val menuItemId: Long,
    val quantity: Int,
    val price: Double,
    val name: String? = null
)

data class RemoteOrderDraft(
    val orderId: Long,
    val type: OrderType,
    val tableId: Long?,
    val total: Double,
    val settlementStatus: String,
    val operationalStatus: String,
    val lines: List<RemoteOrderLine>
)

data class KotItem(
    val id: Long,
    val menuItemId: Long?,
    val name: String,
    val quantity: Int,
    val status: String,
    val note: String? = null
)

data class KotTicket(
    val id: Long,
    val orderId: Long,
    val orderCode: String,
    val type: OrderType,
    val tableName: String?,
    val kitchenPlace: String?,
    val status: String,
    val items: List<KotItem>,
    val createdAtEpochMs: Long? = null
)

data class DiningTable(
    val id: Long,
    val label: String,
    val available: Boolean = true
)

data class BillingLine(
    val orderItemId: Long,
    val menuItemId: Long?,
    val name: String,
    val quantity: Int,
    val unitPrice: Double,
    val amount: Double
)

data class SplitBillInfo(
    val id: Long,
    val label: String,
    val amount: Double,
    val paidAmount: Double,
    val amountDue: Double,
    val status: String
)

data class SessionTableInfo(
    val id: Long,
    val label: String
)

data class MergeCandidate(
    val tableId: Long,
    val label: String,
    val orderIds: List<Long>,
    val amountDue: Double,
    val alreadyMerged: Boolean = false
)

data class GroupOrderDue(
    val orderId: Long,
    val orderNumber: String,
    val tableId: Long?,
    val tableLabel: String?,
    val total: Double,
    val amountPaid: Double,
    val amountDue: Double,
    val status: String
)

data class BillingCapabilities(
    val splitEqual: Boolean = false,
    val splitCustom: Boolean = false,
    val splitItems: Boolean = false,
    val splitPay: Boolean = false,
    val mergeTables: Boolean = false
)

data class BillingContext(
    val orderId: Long,
    val orderNumber: String,
    val total: Double,
    val amountPaid: Double,
    val amountDue: Double,
    val settlementStatus: String,
    val operationalStatus: String,
    val tableId: Long?,
    val diningSessionId: Long?,
    val items: List<BillingLine>,
    val splitBills: List<SplitBillInfo>,
    val sessionTables: List<SessionTableInfo>,
    val groupOrders: List<GroupOrderDue>,
    val groupAmountDue: Double,
    val mergeCandidates: List<MergeCandidate>,
    val capabilities: BillingCapabilities
)

data class DiscoveredPrinter(
    val identifier: String,
    val interfaceType: StarInterfaceType
)

data class CashDenomination(
    val label: String,
    val value: Double,
    val quantity: Int = 0
)

data class CashRegister(
    val id: Long,
    val name: String
)

data class CashSession(
    val id: Long,
    val registerId: Long?,
    val status: String,
    val openingAmount: Double? = null,
    val expectedAmount: Double? = null
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
