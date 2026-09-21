#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import re, shutil

ROOT = Path.cwd()
STAMP = datetime.now().strftime('%Y%m%d_%H%M%S')
BACKUP = ROOT / '_regie_backups' / f'a14_10_android_{STAMP}'
BACKUP.mkdir(parents=True, exist_ok=True)


def backup(path: Path):
    target = BACKUP / path.relative_to(ROOT)
    target.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and not target.exists():
        shutil.copy2(path, target)


def replace_once(file_name: str, old: str, new: str, optional: bool = False):
    path = ROOT / file_name
    if not path.exists():
        raise SystemExit(f'MISSING: {file_name}')
    data = path.read_text()
    if new in data:
        print(f'ALREADY: {file_name}')
        return
    count = data.count(old)
    if count != 1:
        if optional and count == 0:
            print(f'SKIP OPTIONAL: {file_name}: {old[:70]!r}')
            return
        raise SystemExit(f'ANCHOR ERROR: {file_name}: expected 1 occurrence, got {count}: {old[:90]!r}')
    backup(path)
    path.write_text(data.replace(old, new, 1))
    print(f'PATCHED: {file_name}')


def regex_once(file_name: str, pattern: str, repl: str):
    path = ROOT / file_name
    data = path.read_text()
    new, count = re.subn(pattern, repl, data, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f'REGEX ANCHOR ERROR: {file_name}: {pattern}')
    backup(path)
    path.write_text(new)
    print(f'PATCHED REGEX: {file_name}')


def replace_kotlin_function(file_name: str, function_name: str, new_function: str, private: bool = True):
    path = ROOT / file_name
    data = path.read_text()
    markers = [f'private fun {function_name}(', f'fun {function_name}('] if private else [f'fun {function_name}(', f'private fun {function_name}(']
    idx = -1
    marker = None
    for m in markers:
        idx = data.find(m)
        if idx >= 0:
            marker = m
            break
    if idx < 0:
        raise SystemExit(f'FUNCTION NOT FOUND: {file_name}: {function_name}')
    line_start = data.rfind('\n', 0, idx) + 1
    brace = data.find('{', idx)
    if brace < 0:
        raise SystemExit(f'OPEN BRACE NOT FOUND: {function_name}')
    depth = 0
    in_single = in_double = False
    esc = False
    end = None
    i = brace
    while i < len(data):
        ch = data[i]
        if esc:
            esc = False
        elif ch == '\\' and (in_single or in_double):
            esc = True
        elif ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif not in_single and not in_double:
            if ch == '{': depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        i += 1
    if end is None:
        raise SystemExit(f'CLOSE BRACE NOT FOUND: {function_name}')
    backup(path)
    path.write_text(data[:line_start] + new_function.rstrip() + '\n' + data[end:])
    print(f'REPLACED FUNCTION: {file_name}::{function_name}')


MODELS = 'app/src/main/java/be/cookit/pos/android/domain/Models.kt'
CLIENT = 'app/src/main/java/be/cookit/pos/android/data/CookitHttpClient.kt'
VM = 'app/src/main/java/be/cookit/pos/android/ui/CookitPosViewModel.kt'
APP = 'app/src/main/java/be/cookit/pos/android/ui/CookitApp.kt'
LOC = 'app/src/main/java/be/cookit/pos/android/ui/Localization.kt'

# ---------------------------------------------------------------------------
# Domain models: dashboard + delivery. Added fields on PosOrder have defaults,
# so historical/demo positional constructors remain source-compatible.
# ---------------------------------------------------------------------------
replace_once(
    MODELS,
    '''    val remoteStatus: String = "placed",
    val settlementStatus: String = "unknown",
    val createdAtEpochMs: Long? = null
)

data class RemoteOrderLine(''',
    '''    val remoteStatus: String = "placed",
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

data class RemoteOrderLine('''
)

# ---------------------------------------------------------------------------
# HTTP client: Cloud dashboard + delivery APIs and richer createOrder payload.
# ---------------------------------------------------------------------------
client_path = ROOT / CLIENT
client_data = client_path.read_text()
if 'suspend fun dashboard(token: String)' not in client_data:
    anchor = '    suspend fun kots(token: String): List<KotTicket> = withContext(Dispatchers.IO) {'
    if anchor not in client_data:
        raise SystemExit('CLIENT INSERT ANCHOR NOT FOUND: kots()')
    methods = r'''    suspend fun dashboard(token: String): be.cookit.pos.android.domain.DashboardSnapshot = withContext(Dispatchers.IO) {
        val json = request("pos/dashboard", token = token)
        val data = json.optJSONObject("data") ?: json

        val salesJson = data.optJSONArray("sales_data") ?: JSONArray()
        val sales = buildList {
            for (i in 0 until salesJson.length()) {
                val point = salesJson.optJSONObject(i) ?: continue
                add(
                    be.cookit.pos.android.domain.DashboardSalesPoint(
                        date = point.optText("date") ?: "",
                        total = point.optDouble("total", 0.0)
                    )
                )
            }
        }

        val ordersJson = data.optJSONArray("today_orders_list") ?: JSONArray()
        val orders = buildList {
            for (i in 0 until ordersJson.length()) {
                val order = ordersJson.optJSONObject(i) ?: continue
                val id = order.longAny("id", "order_id") ?: continue
                add(
                    be.cookit.pos.android.domain.DashboardOrderSummary(
                        id = id,
                        code = (order.optText("code") ?: "").ifBlank { "#$id" },
                        status = (order.optText("status") ?: "").ifBlank { "placed" },
                        settlementStatus = (order.optText("settlement_status") ?: "").ifBlank { "unknown" },
                        type = mapOrderType((order.optText("order_type") ?: "").ifBlank { "dine_in" }),
                        customer = (order.optText("customer") ?: "").ifBlank { "Client" },
                        table = order.optText("table")?.takeIf { it.isNotBlank() },
                        total = order.optDouble("total", 0.0),
                        createdAtEpochMs = parseServerEpochMs(order.optText("created_at"))
                    )
                )
            }
        }

        be.cookit.pos.android.domain.DashboardSnapshot(
            todayOrders = data.optInt("today_orders", 0),
            todayOrdersChange = data.optDouble("today_orders_change", 0.0),
            todayRevenue = data.optDouble("today_revenue", 0.0),
            todayRevenueChange = data.optDouble("today_revenue_change", 0.0),
            todayCustomers = data.optInt("today_customers", 0),
            todayCustomersChange = data.optDouble("today_customers_change", 0.0),
            averageDailyRevenue = data.optDouble("average_daily_revenue", 0.0),
            averageDailyRevenueChange = data.optDouble("average_daily_revenue_change", 0.0),
            monthlyRevenue = data.optDouble("monthly_revenue", 0.0),
            monthlyRevenueChange = data.optDouble("monthly_revenue_change", 0.0),
            salesData = sales,
            todayOrdersList = orders
        )
    }

    suspend fun deliveryBootstrap(token: String): Pair<List<be.cookit.pos.android.domain.DeliveryExecutive>, be.cookit.pos.android.domain.DeliverySettings?> = withContext(Dispatchers.IO) {
        val json = request("pos/delivery-bootstrap", token = token)
        val data = json.optJSONObject("data") ?: json
        val executivesJson = data.optJSONArray("executives") ?: JSONArray()
        val executives = buildList {
            for (i in 0 until executivesJson.length()) {
                val obj = executivesJson.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "delivery_executive_id") ?: continue
                add(
                    be.cookit.pos.android.domain.DeliveryExecutive(
                        id = id,
                        name = (obj.optText("name") ?: "").ifBlank { "#$id" },
                        status = (obj.optText("status") ?: "").ifBlank { "available" }
                    )
                )
            }
        }
        val settingsJson = data.optJSONObject("settings")
        val settings = settingsJson?.let {
            be.cookit.pos.android.domain.DeliverySettings(
                enabled = it.optBoolean("is_enabled", false),
                feeType = it.optString("fee_type", "fixed"),
                fixedFee = it.optDouble("fixed_fee", 0.0),
                maxRadius = if (it.has("max_radius") && !it.isNull("max_radius")) it.optDouble("max_radius") else null,
                unit = it.optString("unit", "km")
            )
        }
        executives to settings
    }

    suspend fun deliveryOrders(token: String): List<be.cookit.pos.android.domain.DeliveryOrderSummary> = withContext(Dispatchers.IO) {
        val json = request("pos/delivery-orders?limit=100", token = token)
        val array = findArrayDeep(json, setOf("data", "orders")) ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "order_id") ?: continue
                val customerObj = obj.optJSONObject("customer")
                add(
                    be.cookit.pos.android.domain.DeliveryOrderSummary(
                        id = id,
                        code = (obj.optText("formatted_order_number", "order_number") ?: "").let { raw ->
                            if (raw.isBlank()) "#$id" else raw
                        },
                        status = (obj.optText("status") ?: "").ifBlank { "placed" },
                        total = obj.optDouble("total", 0.0),
                        deliveryFee = obj.optDouble("delivery_fee", 0.0),
                        deliveryAddress = obj.optText("delivery_address")?.takeIf { it.isNotBlank() },
                        deliveryExecutiveId = obj.longAny("delivery_executive_id"),
                        customer = customerObj?.optText("name")?.takeIf { it.isNotBlank() } ?: "Client",
                        createdAtEpochMs = parseServerEpochMs(obj.optText("created_at"))
                    )
                )
            }
        }
    }

'''
    backup(client_path)
    client_path.write_text(client_data.replace(anchor, methods + anchor, 1))
    print('ADDED: dashboard/delivery HTTP client methods')
else:
    print('ALREADY: dashboard() HTTP method')

replace_once(
    CLIENT,
    '''    suspend fun createOrder(
        token: String,
        type: OrderType,
        lines: List<CartLine>,
        tableId: Long? = null
    ): Long = withContext(Dispatchers.IO) {''',
    '''    suspend fun createOrder(
        token: String,
        type: OrderType,
        lines: List<CartLine>,
        tableId: Long? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        deliveryAddress: String? = null,
        deliveryFee: Double = 0.0,
        deliveryExecutiveId: Long? = null
    ): Long = withContext(Dispatchers.IO) {'''
)
replace_once(
    CLIENT,
    '''        if (type == OrderType.DINE_IN && tableId != null) body.put("table_id", tableId)

        val json = request("pos/orders", method = "POST", token = token, body = body)''',
    '''        if (type == OrderType.DINE_IN && tableId != null) body.put("table_id", tableId)
        if (type == OrderType.DELIVERY) {
            val customer = JSONObject()
            customerName?.trim()?.takeIf { it.isNotEmpty() }?.let { customer.put("name", it) }
            customerPhone?.trim()?.takeIf { it.isNotEmpty() }?.let { customer.put("phone", it) }
            if (customer.length() > 0) body.put("customer", customer)
            deliveryAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("delivery_address", it) }
            body.put("delivery_fee", deliveryFee.coerceAtLeast(0.0))
            deliveryExecutiveId?.let { body.put("delivery_executive_id", it) }
        }

        val json = request("pos/orders", method = "POST", token = token, body = body)'''
)

# ---------------------------------------------------------------------------
# ViewModel state + dashboard / delivery / retroactive KOT actions.
# ---------------------------------------------------------------------------
replace_once(
    VM,
    '''    val kots: List<KotTicket> = emptyList(),
    val kdsBusyKotIds: Set<Long> = emptySet(),''',
    '''    val kots: List<KotTicket> = emptyList(),
    val dashboard: be.cookit.pos.android.domain.DashboardSnapshot? = null,
    val deliveryExecutives: List<be.cookit.pos.android.domain.DeliveryExecutive> = emptyList(),
    val deliveryOrders: List<be.cookit.pos.android.domain.DeliveryOrderSummary> = emptyList(),
    val deliveryDetailsOpen: Boolean = false,
    val deliveryCustomerName: String = "",
    val deliveryCustomerPhone: String = "",
    val deliveryAddress: String = "",
    val deliveryExecutiveId: Long? = null,
    val deliveryFeeText: String = "0.00",
    val deliveryConfigBusy: Boolean = false,
    val deliveryConfigError: String? = null,
    val orderKotBusyIds: Set<Long> = emptySet(),
    val kdsBusyKotIds: Set<Long> = emptySet(),'''
)

vm_path = ROOT / VM
vm_data = vm_path.read_text()
if 'fun refreshDashboard()' not in vm_data:
    anchor = '    fun requestCheckout() {'
    if anchor not in vm_data:
        raise SystemExit('VIEWMODEL INSERT ANCHOR NOT FOUND: requestCheckout')
    methods = r'''    fun refreshDashboard() {
        val currentToken = token ?: return
        viewModelScope.launch {
            runCatching { api.dashboard(currentToken) }
                .onSuccess { dashboard -> _ui.update { it.copy(dashboard = dashboard) } }
        }
    }

    fun refreshDeliveryOrders() {
        val currentToken = token ?: return
        viewModelScope.launch {
            runCatching {
                val orders = api.deliveryOrders(currentToken)
                val (executives, _) = api.deliveryBootstrap(currentToken)
                orders to executives
            }.onSuccess { (orders, executives) ->
                _ui.update { it.copy(deliveryOrders = orders, deliveryExecutives = executives) }
            }
        }
    }

    fun openDeliveryDetails() {
        _ui.update { it.copy(deliveryDetailsOpen = true, deliveryConfigBusy = true, deliveryConfigError = null) }
        val currentToken = token ?: run {
            _ui.update { it.copy(deliveryConfigBusy = false, deliveryConfigError = "load_failed") }
            return
        }
        viewModelScope.launch {
            runCatching {
                api.deliveryBootstrap(currentToken)
            }.onSuccess { (executives, settings) ->
                _ui.update { state ->
                    val currentFee = state.deliveryFeeText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    val defaultFee = settings?.fixedFee ?: 0.0
                    state.copy(
                        deliveryExecutives = executives,
                        deliveryExecutiveId = state.deliveryExecutiveId ?: executives.singleOrNull()?.id,
                        deliveryFeeText = if (currentFee > 0.0) state.deliveryFeeText else java.lang.String.format(java.util.Locale.US, "%.2f", defaultFee),
                        deliveryConfigBusy = false,
                        deliveryConfigError = null
                    )
                }
            }.onFailure {
                _ui.update { it.copy(deliveryConfigBusy = false, deliveryConfigError = "load_failed") }
            }
        }
    }

    fun dismissDeliveryDetails() {
        _ui.update { it.copy(deliveryDetailsOpen = false, deliveryConfigError = null) }
    }

    fun updateDeliveryCustomerName(value: String) = _ui.update { it.copy(deliveryCustomerName = value) }
    fun updateDeliveryCustomerPhone(value: String) = _ui.update { it.copy(deliveryCustomerPhone = value) }
    fun updateDeliveryAddress(value: String) = _ui.update { it.copy(deliveryAddress = value) }
    fun updateDeliveryFee(value: String) = _ui.update { it.copy(deliveryFeeText = value) }
    fun selectDeliveryExecutive(id: Long) = _ui.update { it.copy(deliveryExecutiveId = id, deliveryConfigError = null) }

    fun confirmDeliveryDetails() {
        val state = _ui.value
        if (state.deliveryAddress.isBlank() || state.deliveryExecutiveId == null) {
            _ui.update { it.copy(deliveryConfigError = "required") }
            return
        }
        _ui.update { it.copy(deliveryDetailsOpen = false, deliveryConfigError = null) }
    }

    fun createKotForOrder(order: PosOrder) {
        if (order.id in _ui.value.orderKotBusyIds) return
        val currentToken = token ?: return
        viewModelScope.launch {
            _ui.update { it.copy(orderKotBusyIds = it.orderKotBusyIds + order.id, orderLoadError = null) }
            runCatching {
                api.ensureKot(currentToken, order.id)
                val freshKots = api.kots(currentToken)
                val freshOrders = api.orders(currentToken)
                freshOrders to freshKots
            }.onSuccess { (orders, kots) ->
                knownOrderIds.addAll(orders.map { it.id })
                _ui.update {
                    it.copy(
                        orders = orders,
                        kots = kots,
                        orderKotBusyIds = it.orderKotBusyIds - order.id,
                        orderLoadError = null
                    )
                }
            }.onFailure {
                _ui.update {
                    it.copy(
                        orderKotBusyIds = it.orderKotBusyIds - order.id,
                        orderLoadError = "kot_create_failed"
                    )
                }
            }
        }
    }

    private fun clearDeliveryDraft() {
        _ui.update {
            it.copy(
                deliveryCustomerName = "",
                deliveryCustomerPhone = "",
                deliveryAddress = "",
                deliveryExecutiveId = null,
                deliveryFeeText = "0.00",
                deliveryDetailsOpen = false,
                deliveryConfigError = null
            )
        }
    }

'''
    backup(vm_path)
    vm_path.write_text(vm_data.replace(anchor, methods + anchor, 1))
    print('ADDED: dashboard/delivery/KOT ViewModel actions')
else:
    print('ALREADY: ViewModel parity actions')

# Delivery must be configured before payment or KOT creation.
replace_once(
    VM,
    '''        if (!resumed && state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        _ui.update {''',
    '''        if (!resumed && state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        if (!resumed && state.draftOrderType == OrderType.DELIVERY && (state.deliveryAddress.isBlank() || state.deliveryExecutiveId == null)) {
            openDeliveryDetails()
            return
        }
        _ui.update {'''
)

# Cloud parity: payment does NOT force a KOT. A KOT is an independent action.
replace_once(
    VM,
    '''                try {
                    api.ensureKot(currentToken, orderId)
                } catch (e: Throwable) {
                    throw IllegalStateException("Envoi en cuisine — ${readableError(e)}", e)
                }

                // Two-phase fiscal guard:''',
    '''                // KOT is deliberately independent from settlement, matching Cookit Cloud.
                // A paid order can receive its KOT later from the Orders screen.

                // Two-phase fiscal guard:'''
)

# Rich delivery payload during direct checkout.
replace_once(
    VM,
    '''                        api.createOrder(
                            currentToken,
                            _ui.value.draftOrderType,
                            _ui.value.draftCart,
                            _ui.value.draftTableId
                        )''',
    '''                        api.createOrder(
                            currentToken,
                            _ui.value.draftOrderType,
                            _ui.value.draftCart,
                            _ui.value.draftTableId,
                            customerName = _ui.value.deliveryCustomerName.takeIf { it.isNotBlank() },
                            customerPhone = _ui.value.deliveryCustomerPhone.takeIf { it.isNotBlank() },
                            deliveryAddress = _ui.value.deliveryAddress.takeIf { it.isNotBlank() },
                            deliveryFee = _ui.value.deliveryFeeText.replace(',', '.').toDoubleOrNull() ?: 0.0,
                            deliveryExecutiveId = _ui.value.deliveryExecutiveId
                        )'''
)
replace_once(
    VM,
    '                finishSuccessfulCheckout(fresh, freshKots, change)',
    '                finishSuccessfulCheckout(fresh, freshKots, change)\n                clearDeliveryDraft()'
)

# Explicit send-to-kitchen also honours delivery details.
replace_once(
    VM,
    '''        if (state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        if (state.demoMode) {''',
    '''        if (state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        if (state.draftOrderType == OrderType.DELIVERY && (state.deliveryAddress.isBlank() || state.deliveryExecutiveId == null)) {
            openDeliveryDetails()
            return
        }
        if (state.demoMode) {'''
)
replace_once(
    VM,
    '''                val orderId = _ui.value.resumedRemoteOrderId ?: _ui.value.pendingRemoteOrderId ?: api.createOrder(
                    currentToken,
                    _ui.value.draftOrderType,
                    _ui.value.draftCart,
                    _ui.value.draftTableId
                ).also { createdId -> updateDraft(pendingOrderId = createdId) }''',
    '''                val orderId = _ui.value.resumedRemoteOrderId ?: _ui.value.pendingRemoteOrderId ?: api.createOrder(
                    currentToken,
                    _ui.value.draftOrderType,
                    _ui.value.draftCart,
                    _ui.value.draftTableId,
                    customerName = _ui.value.deliveryCustomerName.takeIf { it.isNotBlank() },
                    customerPhone = _ui.value.deliveryCustomerPhone.takeIf { it.isNotBlank() },
                    deliveryAddress = _ui.value.deliveryAddress.takeIf { it.isNotBlank() },
                    deliveryFee = _ui.value.deliveryFeeText.replace(',', '.').toDoubleOrNull() ?: 0.0,
                    deliveryExecutiveId = _ui.value.deliveryExecutiveId
                ).also { createdId -> updateDraft(pendingOrderId = createdId) }'''
)
replace_once(
    VM,
    '''                        online = true
                    )
                }
            }.onFailure''',
    '''                        online = true
                    )
                }
                clearDeliveryDraft()
            }.onFailure'''
)

# ---------------------------------------------------------------------------
# Localization expansion: all new operational UI is FR/NL/EN/DE.
# ---------------------------------------------------------------------------
replace_once(
    LOC,
    '    val kitchenServed: String\n)',
    '''    val kitchenServed: String,
    val greeting: String,
    val dashboardTodayOrders: String,
    val dashboardTodayRevenue: String,
    val dashboardTodayCustomers: String,
    val dashboardAverageRevenue: String,
    val dashboardSinceYesterday: String,
    val dashboardSincePreviousMonth: String,
    val dashboardMonthlySales: String,
    val dashboardTodayOrdersList: String,
    val dashboardWaitingOrder: String,
    val ordersHelp: String,
    val paid: String,
    val justNow: String,
    val minutesAgo: String,
    val hoursAgo: String,
    val daysAgo: String,
    val sendToKitchen: String,
    val createKot: String,
    val kotCreateFailed: String,
    val deliveryFlowHelp: String,
    val noActiveDelivery: String,
    val deliveryDetails: String,
    val customerName: String,
    val customerPhone: String,
    val deliveryAddressLabel: String,
    val deliveryDriver: String,
    val deliveryFeeLabel: String,
    val noDeliveryDrivers: String,
    val save: String,
    val deliveryRequired: String,
    val deliveryLoadFailed: String,
    val statusDelivered: String,
    val statusInKitchen: String,
    val statusReady: String,
    val statusPreparing: String,
    val statusPlaced: String,
    val statusConfirmed: String,
    val statusCancelled: String,
    val statusBilled: String
)'''
)

locale_repls = [
('''        kitchenConfirmed="Confirmée", kitchenPreparing="En préparation", kitchenReady="Prête", kitchenServed="Servie"
    )''', '''        kitchenConfirmed="Confirmée", kitchenPreparing="En préparation", kitchenReady="Prête", kitchenServed="Servie",
        greeting="Bonjour", dashboardTodayOrders="Commandes du jour", dashboardTodayRevenue="Revenus du jour",
        dashboardTodayCustomers="Clients du jour", dashboardAverageRevenue="Revenu quotidien moyen",
        dashboardSinceYesterday="depuis hier", dashboardSincePreviousMonth="depuis le mois précédent",
        dashboardMonthlySales="Ventes ce mois-ci", dashboardTodayOrdersList="Commandes aujourd’hui",
        dashboardWaitingOrder="En attente de la première commande d’aujourd’hui",
        ordersHelp="Touchez une commande non payée pour la rouvrir dans la caisse.", paid="Payé",
        justNow="à l’instant", minutesAgo="il y a {n} min", hoursAgo="il y a {n} h", daysAgo="il y a {n} j",
        sendToKitchen="Envoyer en cuisine (KOT)", createKot="Créer KOT", kotCreateFailed="Impossible de créer le KOT.",
        deliveryFlowHelp="Commandes de livraison internes et plateformes dans le même flux.", noActiveDelivery="Aucune livraison active",
        deliveryDetails="Détails de livraison", customerName="Nom du client", customerPhone="Téléphone",
        deliveryAddressLabel="Adresse de livraison", deliveryDriver="Livreur", deliveryFeeLabel="Frais de livraison",
        noDeliveryDrivers="Aucun livreur disponible", save="Enregistrer",
        deliveryRequired="Adresse et livreur requis.", deliveryLoadFailed="Impossible de charger les paramètres de livraison.",
        statusDelivered="Livré", statusInKitchen="En cuisine", statusReady="Prêt", statusPreparing="En préparation",
        statusPlaced="Reçue", statusConfirmed="Confirmée", statusCancelled="Annulée", statusBilled="Facturée"
    )'''),
('''        kitchenConfirmed="Bevestigd", kitchenPreparing="In bereiding", kitchenReady="Klaar", kitchenServed="Geserveerd"
    )''', '''        kitchenConfirmed="Bevestigd", kitchenPreparing="In bereiding", kitchenReady="Klaar", kitchenServed="Geserveerd",
        greeting="Hallo", dashboardTodayOrders="Bestellingen van vandaag", dashboardTodayRevenue="Omzet van vandaag",
        dashboardTodayCustomers="Klanten van vandaag", dashboardAverageRevenue="Gemiddelde dagomzet",
        dashboardSinceYesterday="sinds gisteren", dashboardSincePreviousMonth="sinds vorige maand",
        dashboardMonthlySales="Verkoop deze maand", dashboardTodayOrdersList="Bestellingen vandaag",
        dashboardWaitingOrder="Wachten op de eerste bestelling van vandaag",
        ordersHelp="Tik op een onbetaalde bestelling om ze opnieuw in de kassa te openen.", paid="Betaald",
        justNow="zojuist", minutesAgo="{n} min geleden", hoursAgo="{n} u geleden", daysAgo="{n} d geleden",
        sendToKitchen="Naar keuken sturen (KOT)", createKot="KOT maken", kotCreateFailed="KOT kon niet worden aangemaakt.",
        deliveryFlowHelp="Interne leveringen en platformbestellingen in één stroom.", noActiveDelivery="Geen actieve leveringen",
        deliveryDetails="Leveringsgegevens", customerName="Naam klant", customerPhone="Telefoon",
        deliveryAddressLabel="Leveringsadres", deliveryDriver="Koerier", deliveryFeeLabel="Leveringskosten",
        noDeliveryDrivers="Geen koerier beschikbaar", save="Opslaan",
        deliveryRequired="Adres en koerier zijn verplicht.", deliveryLoadFailed="Leveringsinstellingen konden niet worden geladen.",
        statusDelivered="Geleverd", statusInKitchen="In keuken", statusReady="Klaar", statusPreparing="In bereiding",
        statusPlaced="Ontvangen", statusConfirmed="Bevestigd", statusCancelled="Geannuleerd", statusBilled="Gefactureerd"
    )'''),
('''        kitchenConfirmed="Confirmed", kitchenPreparing="Preparing", kitchenReady="Ready", kitchenServed="Served"
    )''', '''        kitchenConfirmed="Confirmed", kitchenPreparing="Preparing", kitchenReady="Ready", kitchenServed="Served",
        greeting="Hello", dashboardTodayOrders="Today’s orders", dashboardTodayRevenue="Today’s revenue",
        dashboardTodayCustomers="Today’s customers", dashboardAverageRevenue="Average daily revenue",
        dashboardSinceYesterday="since yesterday", dashboardSincePreviousMonth="since previous month",
        dashboardMonthlySales="Sales this month", dashboardTodayOrdersList="Today’s orders",
        dashboardWaitingOrder="Waiting for today’s first order",
        ordersHelp="Tap an unpaid order to reopen it in the POS.", paid="Paid",
        justNow="just now", minutesAgo="{n} min ago", hoursAgo="{n} h ago", daysAgo="{n} d ago",
        sendToKitchen="Send to kitchen (KOT)", createKot="Create KOT", kotCreateFailed="Could not create the KOT.",
        deliveryFlowHelp="Internal delivery and platform orders in the same flow.", noActiveDelivery="No active deliveries",
        deliveryDetails="Delivery details", customerName="Customer name", customerPhone="Phone",
        deliveryAddressLabel="Delivery address", deliveryDriver="Driver", deliveryFeeLabel="Delivery fee",
        noDeliveryDrivers="No driver available", save="Save",
        deliveryRequired="Address and driver are required.", deliveryLoadFailed="Could not load delivery settings.",
        statusDelivered="Delivered", statusInKitchen="In kitchen", statusReady="Ready", statusPreparing="Preparing",
        statusPlaced="Received", statusConfirmed="Confirmed", statusCancelled="Cancelled", statusBilled="Billed"
    )'''),
('''        kitchenConfirmed="Bestätigt", kitchenPreparing="In Zubereitung", kitchenReady="Bereit", kitchenServed="Serviert"
    )''', '''        kitchenConfirmed="Bestätigt", kitchenPreparing="In Zubereitung", kitchenReady="Bereit", kitchenServed="Serviert",
        greeting="Hallo", dashboardTodayOrders="Heutige Bestellungen", dashboardTodayRevenue="Heutiger Umsatz",
        dashboardTodayCustomers="Heutige Kunden", dashboardAverageRevenue="Durchschnittlicher Tagesumsatz",
        dashboardSinceYesterday="seit gestern", dashboardSincePreviousMonth="seit dem Vormonat",
        dashboardMonthlySales="Verkäufe diesen Monat", dashboardTodayOrdersList="Heutige Bestellungen",
        dashboardWaitingOrder="Warten auf die erste Bestellung des Tages",
        ordersHelp="Tippen Sie auf eine unbezahlte Bestellung, um sie in der Kasse wieder zu öffnen.", paid="Bezahlt",
        justNow="gerade eben", minutesAgo="vor {n} Min.", hoursAgo="vor {n} Std.", daysAgo="vor {n} T.",
        sendToKitchen="An Küche senden (KOT)", createKot="KOT erstellen", kotCreateFailed="KOT konnte nicht erstellt werden.",
        deliveryFlowHelp="Interne Lieferungen und Plattformbestellungen in einem Ablauf.", noActiveDelivery="Keine aktiven Lieferungen",
        deliveryDetails="Lieferdetails", customerName="Kundenname", customerPhone="Telefon",
        deliveryAddressLabel="Lieferadresse", deliveryDriver="Fahrer", deliveryFeeLabel="Liefergebühr",
        noDeliveryDrivers="Kein Fahrer verfügbar", save="Speichern",
        deliveryRequired="Adresse und Fahrer sind erforderlich.", deliveryLoadFailed="Liefereinstellungen konnten nicht geladen werden.",
        statusDelivered="Geliefert", statusInKitchen="In Küche", statusReady="Bereit", statusPreparing="In Zubereitung",
        statusPlaced="Eingegangen", statusConfirmed="Bestätigt", statusCancelled="Storniert", statusBilled="Abgerechnet"
    )''')
]
for old, new in locale_repls:
    replace_once(LOC, old, new)

# ---------------------------------------------------------------------------
# Compose screen wiring + new operational UI.
# ---------------------------------------------------------------------------
replace_once(APP, '        Screen.DASHBOARD -> DashboardScreen(state)', '        Screen.DASHBOARD -> DashboardScreen(state, vm, t)')
regex_once(APP, r'^\s*Screen\.ORDERS -> OrdersScreen\([^\n]+\)$', '        Screen.ORDERS -> OrdersScreen(state, vm::openOrderForPos, vm::createKotForOrder, t)')
replace_once(APP, '        Screen.DELIVERY -> DeliveryScreen(state.orders, t)', '        Screen.DELIVERY -> DeliveryScreen(state, vm, t)')
replace_once(
    APP,
    '            OrderTypeChip(t.deliveryType, orderType == OrderType.DELIVERY) { vm.setDraftOrderType(OrderType.DELIVERY) }',
    '''            OrderTypeChip(t.deliveryType, orderType == OrderType.DELIVERY) {
                vm.setDraftOrderType(OrderType.DELIVERY)
                vm.openDeliveryDetails()
            }
            if (state.deliveryDetailsOpen) {
                DeliveryOrderDialog(state = state, vm = vm, t = t)
            }'''
)
replace_once(APP, '                    Text("Envoyer en cuisine (KOT)", fontWeight = FontWeight.Black)', '                    Text(t.sendToKitchen, fontWeight = FontWeight.Black)', optional=True)
replace_once(APP, '                    Text("Rafraîchir")', '                    Text(t.refresh)', optional=True)
replace_once(APP, '                    Text("Nouvelle commande")', '                    Text(t.newOrder)', optional=True)
replace_once(APP, '        Text("Flux KOT réel Cookit : les changements mettent à jour le suivi de commande.", color = CookitMuted)', '        Text(t.kitchenFlowHelp, color = CookitMuted)')

# Insert delivery dialog + shared status helpers before OrdersScreen.
app_path = ROOT / APP
app_data = app_path.read_text()
if 'private fun DeliveryOrderDialog(' not in app_data:
    anchor = '@Composable\nprivate fun OrdersScreen('
    if anchor not in app_data:
        raise SystemExit('APP INSERT ANCHOR NOT FOUND: OrdersScreen')
    helpers = r'''@Composable
private fun DeliveryOrderDialog(
    state: PosUiState,
    vm: CookitPosViewModel,
    t: UiStrings
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = vm::dismissDeliveryDetails) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.deliveryDetails, fontSize = 24.sp, fontWeight = FontWeight.Black)
                OutlinedTextField(
                    value = state.deliveryCustomerName,
                    onValueChange = vm::updateDeliveryCustomerName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.customerName) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.deliveryCustomerPhone,
                    onValueChange = vm::updateDeliveryCustomerPhone,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.customerPhone) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.deliveryAddress,
                    onValueChange = vm::updateDeliveryAddress,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.deliveryAddressLabel) },
                    minLines = 2
                )
                OutlinedTextField(
                    value = state.deliveryFeeText,
                    onValueChange = vm::updateDeliveryFee,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.deliveryFeeLabel) },
                    singleLine = true
                )

                Text(t.deliveryDriver, fontWeight = FontWeight.Bold)
                if (state.deliveryConfigBusy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (state.deliveryExecutives.isEmpty()) {
                    Text(t.noDeliveryDrivers, color = CookitMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.deliveryExecutives.forEach { executive ->
                            val selected = executive.id == state.deliveryExecutiveId
                            OutlinedButton(
                                onClick = { vm.selectDeliveryExecutive(executive.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (selected) CookitOrange else CookitLine)
                            ) {
                                Text(
                                    executive.name,
                                    color = if (selected) CookitOrange else CookitInk,
                                    fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                state.deliveryConfigError?.let { error ->
                    Text(
                        if (error == "required") t.deliveryRequired else t.deliveryLoadFailed,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = vm::dismissDeliveryDetails) { Text(t.cancel) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = vm::confirmDeliveryDetails,
                        enabled = !state.deliveryConfigBusy && state.deliveryExecutives.isNotEmpty()
                    ) { Text(t.save, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

private fun localizedOrderStatus(raw: String, t: UiStrings): String {
    return when (raw.trim().lowercase(Locale.ROOT)) {
        "paid" -> t.paid
        "delivered", "livré", "livree", "livrée", "geleverd", "geliefert" -> t.statusDelivered
        "in_kitchen", "en cuisine", "in kitchen", "in keuken", "in küche" -> t.statusInKitchen
        "food_ready", "ready", "prêt", "prete", "prête", "klaar", "bereit" -> t.statusReady
        "preparing", "cooking", "en préparation", "in bereiding", "in zubereitung" -> t.statusPreparing
        "placed", "received", "reçue", "ontvangen", "eingegangen" -> t.statusPlaced
        "confirmed", "confirmée", "bevestigd", "bestätigt" -> t.statusConfirmed
        "canceled", "cancelled", "annulée", "geannuleerd", "storniert" -> t.statusCancelled
        "billed", "facturée", "gefactureerd", "abgerechnet" -> t.statusBilled
        else -> raw.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

private fun localizedRelativeAge(minutes: Int, t: UiStrings): String = when {
    minutes < 1 -> t.justNow
    minutes < 60 -> t.minutesAgo.replace("{n}", minutes.toString())
    minutes < 1440 -> t.hoursAgo.replace("{n}", (minutes / 60).toString())
    else -> t.daysAgo.replace("{n}", (minutes / 1440).toString())
}

private fun dashboardChange(value: Double, suffix: String): String {
    val sign = if (value > 0.0) "+" else ""
    return "$sign${String.format(Locale.FRANCE, "%.1f", value)}% $suffix"
}

'''
    backup(app_path)
    app_path.write_text(app_data.replace(anchor, helpers + anchor, 1))
    print('ADDED: delivery dialog + localization helpers')
else:
    print('ALREADY: DeliveryOrderDialog')

orders_screen = r'''private fun OrdersScreen(
    state: PosUiState,
    onOpen: (PosOrder) -> Unit,
    onCreateKot: (PosOrder) -> Unit,
    t: UiStrings
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(t.orders, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(t.ordersHelp, color = CookitMuted)

        state.orderLoadError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    if (error == "kot_create_failed") t.kotCreateFailed else error,
                    Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.orders, key = { it.id }) { order ->
                val settlement = order.settlementStatus.lowercase(Locale.ROOT)
                val paid = settlement in setOf("paid", "refunded", "completed")
                val cancelled = settlement in setOf("cancelled", "canceled") || order.remoteStatus.lowercase(Locale.ROOT) in setOf("cancelled", "canceled")
                val hasKot = state.kots.any { it.orderId == order.id }
                val kotBusy = order.id in state.orderKotBusyIds

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !paid && !cancelled && !state.orderLoadBusy) { onOpen(order) },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CookitLine)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (order.unread) {
                            Box(
                                Modifier.size(10.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CookitOrange)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(order.code, fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(8.dp))
                                Text(order.channel, color = CookitOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                order.table?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Text(it, color = CookitMuted, fontSize = 12.sp)
                                }
                            }
                            Text(
                                "${order.customer} • ${localizedRelativeAge(order.minutesAgo, t)}",
                                color = CookitMuted,
                                fontSize = 12.sp
                            )
                            if (!cancelled && !hasKot) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { onCreateKot(order) },
                                    enabled = !kotBusy,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (kotBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text(t.createKot, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(shape = RoundedCornerShape(10.dp), color = CookitSoftGreen) {
                                    Text(
                                        localizedOrderStatus(order.status, t),
                                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = CookitGreen,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (paid) CookitSoftGreen else CookitSoftOrange
                                ) {
                                    Text(
                                        if (paid) t.paid else settlement.replace('_', ' ').uppercase(Locale.getDefault()),
                                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = if (paid) CookitGreen else CookitOrange,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(String.format(Locale.FRANCE, "%.2f €", order.total), fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}'''
replace_kotlin_function(APP, 'OrdersScreen', orders_screen)

# Remove the old unlocalized relativeAge helper if present; calls now use localizedRelativeAge.
replace_once(
    APP,
    '''private fun relativeAge(minutes: Int): String = when {
    minutes < 1 -> "à l'instant"
    minutes < 60 -> "il y a $minutes min"
    minutes < 1440 -> "il y a ${minutes / 60} h"
    else -> "il y a ${minutes / 1440} j"
}

''',
    '',
    optional=True
)

kds_screen = r'''private fun KdsScreen(
    state: PosUiState,
    t: UiStrings,
    onAdvance: (KotTicket) -> Unit
) {
    val activeTickets = state.kots.filter {
        it.status.lowercase(Locale.ROOT) !in setOf("served", "cancelled", "canceled")
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(t.kitchen, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(t.kitchenFlowHelp, color = CookitMuted)
        state.kdsError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (activeTickets.isEmpty()) {
            EmptyOperationalState(Icons.Default.SoupKitchen, t.noKitchenTickets)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(activeTickets, key = { it.id }) { ticket ->
                    KotTicketCard(
                        ticket = ticket,
                        t = t,
                        busy = ticket.id in state.kdsBusyKotIds,
                        onAdvance = { onAdvance(ticket) }
                    )
                }
            }
        }
    }
}'''
replace_kotlin_function(APP, 'KdsScreen', kds_screen)

delivery_screen = r'''private fun DeliveryScreen(state: PosUiState, vm: CookitPosViewModel, t: UiStrings) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshDeliveryOrders() }
    val executiveNames = state.deliveryExecutives.associate { it.id to it.name }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(t.delivery, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(t.deliveryFlowHelp, color = CookitMuted)
        Spacer(Modifier.height(16.dp))

        if (state.deliveryOrders.isEmpty()) {
            EmptyOperationalState(Icons.Default.DeliveryDining, t.noActiveDelivery)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.deliveryOrders, key = { it.id }) { order ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, CookitLine)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(order.code, fontSize = 18.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.weight(1f))
                                Surface(shape = RoundedCornerShape(12.dp), color = CookitSoftGreen) {
                                    Text(
                                        localizedOrderStatus(order.status, t),
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = CookitGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(order.customer, fontWeight = FontWeight.SemiBold)
                            order.deliveryAddress?.let { Text(it, color = CookitMuted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            order.deliveryExecutiveId?.let { id ->
                                Text("${t.deliveryDriver}: ${executiveNames[id] ?: "#$id"}", color = CookitMuted, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = CookitLine)
                            Row(Modifier.fillMaxWidth()) {
                                Text(String.format(Locale.FRANCE, "%.2f €", order.total), fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Spacer(Modifier.weight(1f))
                                if (order.deliveryFee > 0.0) {
                                    Text("${t.deliveryFeeLabel}: ${String.format(Locale.FRANCE, "%.2f €", order.deliveryFee)}", color = CookitMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}'''
replace_kotlin_function(APP, 'DeliveryScreen', delivery_screen)

dashboard_screen = r'''private fun DashboardScreen(state: PosUiState, vm: CookitPosViewModel, t: UiStrings) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshDashboard() }
    val dashboard = state.dashboard
    val firstName = state.user.name.substringBefore(' ')

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("${t.greeting} $firstName", fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("${state.user.restaurant} • ${state.user.branch}", color = CookitMuted)
        Spacer(Modifier.height(18.dp))

        if (dashboard == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(t.dashboardTodayRevenue, String.format(Locale.FRANCE, "%.2f €", state.orders.sumOf { it.total }), "—", Icons.Default.TrendingUp, Modifier.weight(1f))
                MetricCard(t.dashboardTodayOrders, state.orders.size.toString(), "—", Icons.Default.ReceiptLong, Modifier.weight(1f))
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(t.dashboardTodayOrders, dashboard.todayOrders.toString(), dashboardChange(dashboard.todayOrdersChange, t.dashboardSinceYesterday), Icons.Default.ReceiptLong, Modifier.weight(1f))
            MetricCard(t.dashboardTodayRevenue, String.format(Locale.FRANCE, "%.2f €", dashboard.todayRevenue), dashboardChange(dashboard.todayRevenueChange, t.dashboardSinceYesterday), Icons.Default.TrendingUp, Modifier.weight(1f))
            MetricCard(t.dashboardTodayCustomers, dashboard.todayCustomers.toString(), dashboardChange(dashboard.todayCustomersChange, t.dashboardSinceYesterday), Icons.Default.ReceiptLong, Modifier.weight(1f))
            MetricCard(t.dashboardAverageRevenue, String.format(Locale.FRANCE, "%.2f €", dashboard.averageDailyRevenue), dashboardChange(dashboard.averageDailyRevenueChange, t.dashboardSincePreviousMonth), Icons.Default.Payments, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(
                modifier = Modifier.weight(2f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, CookitLine)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(t.dashboardMonthlySales, color = CookitMuted, fontWeight = FontWeight.SemiBold)
                    Text(String.format(Locale.FRANCE, "%.2f €", dashboard.monthlyRevenue), fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(dashboardChange(dashboard.monthlyRevenueChange, t.dashboardSincePreviousMonth), color = CookitGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    DashboardSalesChart(dashboard.salesData)
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, CookitLine)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t.dashboardTodayOrdersList, fontWeight = FontWeight.Black)
                    if (dashboard.todayOrdersList.isEmpty()) {
                        Text(t.dashboardWaitingOrder, color = CookitMuted)
                    } else {
                        dashboard.todayOrdersList.take(6).forEach { order ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(order.code, fontWeight = FontWeight.Bold)
                                    Text(localizedOrderStatus(order.status, t), color = CookitMuted, fontSize = 11.sp)
                                }
                                Text(String.format(Locale.FRANCE, "%.2f €", order.total), fontWeight = FontWeight.Black)
                            }
                            HorizontalDivider(color = CookitLine)
                        }
                    }
                }
            }
        }
    }
}'''
replace_kotlin_function(APP, 'DashboardScreen', dashboard_screen)

# Replace MetricCard to support the Cloud-like four-card row.
metric_screen = r'''private fun MetricCard(
    title: String,
    value: String,
    note: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, CookitLine)
    ) {
        Column(Modifier.padding(18.dp)) {
            Icon(icon, null, tint = CookitOrange)
            Spacer(Modifier.height(16.dp))
            Text(title, color = CookitMuted, fontSize = 12.sp)
            Text(value, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(note, color = CookitGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}'''
replace_kotlin_function(APP, 'MetricCard', metric_screen)

# Insert dependency-free Compose Canvas chart immediately before MetricCard.
app_data = app_path.read_text()
if 'private fun DashboardSalesChart(' not in app_data:
    anchor = '@Composable\nprivate fun MetricCard('
    if anchor not in app_data:
        raise SystemExit('CHART INSERT ANCHOR NOT FOUND: MetricCard')
    chart = r'''@Composable
private fun DashboardSalesChart(points: List<be.cookit.pos.android.domain.DashboardSalesPoint>) {
    if (points.isEmpty()) {
        Spacer(Modifier.height(190.dp))
        return
    }
    val totals = points.map { it.total }
    val min = totals.minOrNull() ?: 0.0
    val max = totals.maxOrNull() ?: 0.0
    val range = (max - min).takeIf { it > 0.0001 } ?: 1.0

    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(190.dp)) {
        val stepX = if (points.size <= 1) 0f else size.width / (points.size - 1).toFloat()
        for (grid in 0..4) {
            val y = size.height * grid / 4f
            drawLine(CookitLine, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1f)
        }
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { index, point ->
            val x = if (points.size <= 1) size.width / 2f else index * stepX
            val ratio = ((point.total - min) / range).toFloat()
            val y = size.height - (ratio * (size.height * 0.82f)) - size.height * 0.09f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, CookitOrange, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
    Row(Modifier.fillMaxWidth()) {
        Text(points.first().date, color = CookitMuted, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(points.last().date, color = CookitMuted, fontSize = 10.sp)
    }
}

'''
    backup(app_path)
    app_path.write_text(app_data.replace(anchor, chart + anchor, 1))
    print('ADDED: DashboardSalesChart')

# Version bump. Fiscal C5.3 sources are deliberately untouched.
replace_once('app/build.gradle.kts', 'versionCode = 31', 'versionCode = 32')
replace_once('app/build.gradle.kts', 'versionName = "0.14.9.1"', 'versionName = "0.14.10"')

print('\nA14.10 ANDROID CLOUD PARITY + UI POLISH PATCH COMPLETE')
print(f'Backup: {BACKUP.relative_to(ROOT)}')
