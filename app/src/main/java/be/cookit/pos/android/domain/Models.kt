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

enum class HardwareMode {
    REAL,
    SIMULATED
}

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
    val vatLabel: String? = null,
    val hasModifiers: Boolean = false
)

data class NativeModifierOption(
    val id: Long,
    val groupId: Long,
    val name: String,
    val price: Double,
    val available: Boolean = true,
    val preselected: Boolean = false
)

data class NativeModifierGroup(
    val id: Long,
    val name: String,
    val required: Boolean,
    val allowMultiple: Boolean,
    val options: List<NativeModifierOption>
)

data class SelectedModifier(
    val id: Long,
    val groupId: Long,
    val name: String,
    val price: Double
)

data class CartLine(
    val product: Product,
    val quantity: Int,
    val remoteLineId: Long? = null,
    val amountOverride: Double? = null,
    val freeItem: Boolean = false,
    val modifiers: List<SelectedModifier> = emptyList()
) {
    val modifierUnitTotal: Double get() = modifiers.sumOf { it.price }
    val configuredUnitPrice: Double get() = product.price + modifierUnitTotal
    val total: Double get() = amountOverride ?: configuredUnitPrice * quantity
    val modifierKey: String
        get() = modifiers.map { it.id }.sorted().joinToString("-")
    val stableKey: String
        get() = remoteLineId?.let { "remote:$it" }
            ?: "product:${product.id}:mods:$modifierKey"
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
    val vatLabel: String? = null,
    val orderItemId: Long? = null,
    val amount: Double? = null,
    val freeItem: Boolean = false,
    val modifiers: List<SelectedModifier> = emptyList()
)

data class CommercialDiscountState(
    val type: String? = null,
    val value: Double = 0.0,
    val amount: Double = 0.0
)

data class CommercialTipState(
    val amount: Double = 0.0,
    val note: String? = null
)

data class CommercialLoyaltyState(
    val pointsRedeemed: Int = 0,
    val discountAmount: Double = 0.0,
    val stampDiscountAmount: Double = 0.0,
    val stampDiscountEmbeddedInItems: Boolean = true
)

data class CommercialTotals(
    val subTotal: Double = 0.0,
    val chargesTotal: Double = 0.0,
    val taxTotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val grandTotal: Double = 0.0
)

data class CommercialSnapshot(
    val contractVersion: String = "",
    val orderId: Long = 0L,
    val discount: CommercialDiscountState = CommercialDiscountState(),
    val tip: CommercialTipState = CommercialTipState(),
    val loyalty: CommercialLoyaltyState = CommercialLoyaltyState(),
    val totals: CommercialTotals = CommercialTotals()
)

data class LoyaltyCustomer(
    val id: Long,
    val name: String,
    val phone: String
)

data class LoyaltyPointsSummary(
    val enabled: Boolean = false,
    val availablePoints: Int = 0,
    val pointsValue: Double = 0.0,
    val maxDiscount: Double = 0.0,
    val pointsRequired: Int = 0,
    val minRedeemPoints: Int = 0,
    val valuePerPoint: Double = 0.0,
    val maxDiscountPercent: Double = 0.0
)

data class LoyaltyStampRuleSummary(
    val ruleId: Long,
    val menuItemId: Long,
    val menuItemName: String,
    val stampsRequired: Int,
    val availableStamps: Int,
    val canRedeem: Boolean,
    val eligibleQuantity: Int,
    val redeemedQuantity: Int,
    val remainingEligibleQuantity: Int,
    val redeemableQuantity: Int,
    val rewardType: String,
    val rewardValue: Double,
    val rewardMenuItemId: Long?,
    val rewardMenuItemName: String,
    val rewardMenuItemVariationId: Long? = null
)

data class LoyaltyOrderState(
    val id: Long,
    val customerId: Long?,
    val status: String,
    val settlementStatus: String,
    val subtotal: Double,
    val total: Double,
    val manualDiscountAmount: Double,
    val loyaltyPointsRedeemed: Int,
    val loyaltyDiscountAmount: Double,
    val stampDiscountAmount: Double,
    val freeStampItemCount: Int
)

data class LoyaltySummary(
    val contractVersion: String = "",
    val moduleEnabled: Boolean = false,
    val programEnabled: Boolean = false,
    val posEnabled: Boolean = false,
    val settingsConfigured: Boolean = false,
    val customer: LoyaltyCustomer? = null,
    val points: LoyaltyPointsSummary = LoyaltyPointsSummary(),
    val stampsEnabled: Boolean = false,
    val stampRules: List<LoyaltyStampRuleSummary> = emptyList(),
    val order: LoyaltyOrderState? = null,
    val customerRequired: Boolean = false
)

data class LoyaltyMutationResult(
    val success: Boolean,
    val operation: String,
    val orderId: Long,
    val pointsRedeemed: Int = 0,
    val discountAmount: Double = 0.0,
    val removed: Boolean = false,
    val alreadyClear: Boolean = false,
    val ruleId: Long? = null,
    val stampsRedeemed: Int = 0,
    val redeemedQuantity: Int = 0,
    val rewardType: String = "",
    val rewardValue: Double = 0.0,
    val rewardMenuItemId: Long? = null,
    val commercial: CommercialSnapshot? = null,
    val loyaltyOrder: LoyaltyOrderState? = null,
    val clientOperationId: String = "",
    val idempotentReplay: Boolean = false
)

data class NativeCertificationCapabilities(
    val contractVersion: String = "",
    val manualDiscount: Boolean = false,
    val tip: Boolean = false,
    val loyaltyPoints: Boolean = false,
    val stampRewards: Boolean = false,
    val modifiers: Boolean = false
) {
    val commercialToolsAvailable: Boolean
        get() = manualDiscount || tip || loyaltyPoints || stampRewards
}

data class RemoteOrderDraft(
    val orderId: Long,
    val type: OrderType,
    val tableId: Long?,
    val total: Double,
    val settlementStatus: String,
    val operationalStatus: String,
    val lines: List<RemoteOrderLine>,
    val customerId: Long? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val commercial: CommercialSnapshot = CommercialSnapshot()
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

data class HardwareSimulationEvent(
    val timestampEpochMs: Long,
    val action: String,
    val result: String,
    val detail: String? = null
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
