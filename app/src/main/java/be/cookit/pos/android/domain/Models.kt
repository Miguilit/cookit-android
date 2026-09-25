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
    val restaurantLogoUrl: String? = null,
    val restaurantId: Long? = null,
    val branchId: Long? = null
)

data class FiscalRuntimeIdentity(
    val runtimeId: String,
    val terminalId: String,
    val deviceId: String,
    val createdAtEpochMs: Long,
    val restaurantId: Long? = null,
    val branchId: Long? = null,
    val restaurantName: String? = null,
    val branchName: String? = null
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
    val imageUrl: String? = null,
    val vatRate: Double? = null,
    val vatLabel: String? = null
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
    val createdAtEpochMs: Long? = null,
    val deliveryAddress: String? = null,
    val deliveryFee: Double = 0.0,
    val deliveryExecutiveId: Long? = null
)

data class DashboardSalesPoint(
    val date: String,
    val total: Double
)

data class DashboardOrderSummary(
    val id: Long,
    val code: String,
    val status: String,
    val settlementStatus: String,
    val type: OrderType,
    val customer: String,
    val table: String? = null,
    val total: Double,
    val createdAtEpochMs: Long? = null
)

data class DashboardSnapshot(
    val todayOrders: Int,
    val todayOrdersChange: Double,
    val todayRevenue: Double,
    val todayRevenueChange: Double,
    val todayCustomers: Int,
    val todayCustomersChange: Double,
    val averageDailyRevenue: Double,
    val averageDailyRevenueChange: Double,
    val monthlyRevenue: Double,
    val monthlyRevenueChange: Double,
    val salesData: List<DashboardSalesPoint>,
    val todayOrdersList: List<DashboardOrderSummary>
)

data class DeliveryExecutive(
    val id: Long,
    val name: String,
    val status: String
)

data class DeliverySettings(
    val enabled: Boolean,
    val feeType: String,
    val fixedFee: Double,
    val maxRadius: Double? = null,
    val unit: String = "km"
)

data class DeliveryOrderSummary(
    val id: Long,
    val code: String,
    val status: String,
    val total: Double,
    val deliveryFee: Double,
    val deliveryAddress: String?,
    val deliveryExecutiveId: Long?,
    val customer: String,
    val createdAtEpochMs: Long? = null
)

data class RemoteOrderLine(
    val menuItemId: Long,
    val quantity: Int,
    val price: Double,
    val name: String? = null,
    val categoryId: Long? = null,
    val vatRate: Double? = null,
    val vatLabel: String? = null
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
    val quantity: Int = 0,
    val id: Long? = null,
    val uuid: String? = null,
    val type: String? = null
)

data class CashRegister(
    val id: Long,
    val name: String,
    val isActive: Boolean = true
)

data class CashMovement(
    val id: Long,
    val type: String,
    val amount: Double,
    val runningAmount: Double? = null,
    val reason: String? = null,
    val reference: String? = null,
    val happenedAt: String? = null,
    val createdBy: Long? = null
)

data class CashRegisterTotals(
    val openingFloat: Double = 0.0,
    val cashSales: Double = 0.0,
    val cashIn: Double = 0.0,
    val cashOut: Double = 0.0,
    val safeDrops: Double = 0.0,
    val refunds: Double = 0.0,
    val runningTotal: Double = 0.0
)

data class CashRegisterSummary(
    val totals: CashRegisterTotals = CashRegisterTotals(),
    val expectedCash: Double = 0.0,
    val countedCash: Double = 0.0,
    val physicalCashCounted: Double? = null,
    val discrepancy: Double = 0.0,
    val transactionsCount: Int = 0
)

data class CashSession(
    val id: Long,
    val registerId: Long?,
    val status: String,
    val openingAmount: Double? = null,
    val expectedAmount: Double? = null,
    val registerName: String? = null,
    val openedAt: String? = null,
    val closedAt: String? = null,
    val physicalCashCounted: Double? = null,
    val countedCash: Double? = null,
    val discrepancy: Double? = null,
    val closingAttemptId: String? = null,
    val transactionsCount: Int = 0
)

data class CashReportDenomination(
    val label: String,
    val value: Double,
    val count: Int,
    val subtotal: Double
)

data class CashRegisterReport(
    val reportType: String,
    val sessionId: Long,
    val sessionStatus: String,
    val registerName: String? = null,
    val cashierName: String? = null,
    val openedAt: String? = null,
    val closedAt: String? = null,
    val generatedAt: String? = null,
    val openingFloat: Double = 0.0,
    val cashSales: Double = 0.0,
    val paymentMethodTotals: Map<String, Double> = emptyMap(),
    val totalPayments: Double = 0.0,
    val changeGiven: Double = 0.0,
    val cashIn: Double = 0.0,
    val cashOut: Double = 0.0,
    val safeDrops: Double = 0.0,
    val refunds: Double = 0.0,
    val expectedCash: Double = 0.0,
    val countedCash: Double? = null,
    val physicalCashCounted: Double? = null,
    val discrepancy: Double? = null,
    val denominations: List<CashReportDenomination> = emptyList()
)

data class NativePolicy(
    val profile: PosRole,
    val cashSessionRequired: Boolean,
    val canManageSettings: Boolean,
    val canManagePrinters: Boolean,
    val canUsePos: Boolean,
    val canViewKds: Boolean,
    val canViewDelivery: Boolean,
    val canApproveCashRegister: Boolean = false,
    val canViewCashRegisterReports: Boolean = false
)
