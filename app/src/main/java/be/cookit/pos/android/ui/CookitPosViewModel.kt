package be.cookit.pos.android.ui

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.cookit.pos.android.data.*
import be.cookit.pos.android.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PosUiState(
    val authenticated: Boolean = false,
    val demoMode: Boolean = false,
    val loading: Boolean = false,
    val online: Boolean = false,
    val error: String? = null,
    val user: UserSession = DemoRepository.user,
    val policy: NativePolicy = DemoRepository.policy,
    val categories: List<Category> = DemoRepository.categories,
    val products: List<Product> = DemoRepository.products,
    val orders: List<PosOrder> = DemoRepository.orders,
    val tables: List<DiningTable> = emptyList(),
    val unreadInbound: Int = 0,
    val lastSyncEpochMs: Long? = null,
    val language: AppLanguage = AppLanguage.FR,
    val cashRegisters: List<CashRegister> = emptyList(),
    val cashDenominations: List<CashDenomination> = DemoRepository.denominations,
    val activeCashSession: CashSession? = null,
    val cashBusy: Boolean = false,
    val checkoutBusy: Boolean = false,
    val checkoutMessage: String? = null,
    val checkoutError: String? = null,
    val checkoutNonce: Long = 0,
    val paymentSheetOpen: Boolean = false,
    val draftCart: List<CartLine> = emptyList(),
    val draftOrderType: OrderType = OrderType.DINE_IN,
    val draftTableId: Long? = null,
    val pendingRemoteOrderId: Long? = null,
    val printerProvider: PrinterProviderType = PrinterProviderType.ESC_POS,
    val printerHost: String = "",
    val printerPort: Int = 9100,
    val starIdentifier: String = "",
    val starInterface: StarInterfaceType = StarInterfaceType.LAN,
    val discoveredPrinters: List<DiscoveredPrinter> = emptyList(),
    val printerDiscoveryBusy: Boolean = false,
    val printerMessage: String? = null
)

class CookitPosViewModel(application: Application) : AndroidViewModel(application) {
    private val api = CookitHttpClient()
    private val sessionStore = SessionStore(application)
    private val draftStore = DraftOrderStore(application)
    private val printerStore = PrinterSettingsStore(application)
    private val printerService = EscPosPrinterService()
    private val starPrinterService = StarPrinterService(application)
    private val starDiscoveryService = StarDiscoveryService(application)
    private var persistedDraft = draftStore.load()

    private val _ui = MutableStateFlow(
        PosUiState(
            language = sessionStore.language(),
            draftOrderType = persistedDraft.orderType,
            draftTableId = persistedDraft.tableId,
            pendingRemoteOrderId = persistedDraft.pendingOrderId,
            printerProvider = printerStore.provider(),
            printerHost = printerStore.host(),
            printerPort = printerStore.port(),
            starIdentifier = printerStore.starIdentifier(),
            starInterface = printerStore.starInterface()
        )
    )
    val ui: StateFlow<PosUiState> = _ui.asStateFlow()

    private var token: String? = null
    private var pollingJob: Job? = null
    private val knownOrderIds = linkedSetOf<Long>()
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    init {
        api.languageCode = sessionStore.language().code
        val savedToken = sessionStore.token()
        val savedEmail = sessionStore.email()
        if (!savedToken.isNullOrBlank() && !savedEmail.isNullOrBlank()) {
            token = savedToken
            viewModelScope.launch { bootstrap(savedEmail) }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _ui.update { it.copy(error = "E-mail et mot de passe requis.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val newToken = api.login(email, password)
                token = newToken
                sessionStore.save(newToken, email.trim())
                bootstrap(email.trim())
            }.onFailure { throwable ->
                token = null
                sessionStore.clearSession()
                _ui.update { it.copy(authenticated = false, loading = false, online = false, error = readableError(throwable)) }
            }
        }
    }

    fun enterDemo() {
        pollingJob?.cancel()
        _ui.update {
            PosUiState(
                authenticated = true,
                demoMode = true,
                online = true,
                language = it.language,
                printerProvider = it.printerProvider,
                printerHost = it.printerHost,
                printerPort = it.printerPort,
                starIdentifier = it.starIdentifier,
                starInterface = it.starInterface
            )
        }
    }

    fun logout() {
        pollingJob?.cancel()
        token = null
        knownOrderIds.clear()
        sessionStore.clearSession()
        draftStore.clear()
        persistedDraft = PersistedDraft()
        _ui.update {
            PosUiState(
                language = it.language,
                printerProvider = it.printerProvider,
                printerHost = it.printerHost,
                printerPort = it.printerPort,
                starIdentifier = it.starIdentifier,
                starInterface = it.starInterface
            )
        }
    }

    fun setLanguage(language: AppLanguage) {
        sessionStore.saveLanguage(language)
        api.languageCode = language.code
        _ui.update { it.copy(language = language) }
        if (token != null) refresh()
    }

    fun setPrinterProvider(provider: PrinterProviderType) {
        printerStore.saveProvider(provider)
        _ui.update { it.copy(printerProvider = provider, printerMessage = null) }
    }

    fun savePrinter(host: String, portText: String) {
        val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 9100
        printerStore.saveEscPos(host, port)
        _ui.update { it.copy(printerHost = host.trim(), printerPort = port, printerMessage = null) }
    }

    fun saveStarPrinter(identifier: String, interfaceType: StarInterfaceType) {
        printerStore.saveStar(identifier, interfaceType)
        _ui.update {
            it.copy(
                starIdentifier = identifier.trim(),
                starInterface = interfaceType,
                printerMessage = null
            )
        }
    }

    fun discoverStarPrinters(interfaceType: StarInterfaceType) {
        viewModelScope.launch {
            _ui.update { it.copy(printerDiscoveryBusy = true, discoveredPrinters = emptyList(), printerMessage = null) }
            runCatching { starDiscoveryService.discover(interfaceType) }
                .onSuccess { printers ->
                    _ui.update {
                        it.copy(
                            printerDiscoveryBusy = false,
                            discoveredPrinters = printers,
                            printerMessage = if (printers.isEmpty()) "discovery_empty" else "discovery_ok"
                        )
                    }
                }
                .onFailure {
                    _ui.update { it.copy(printerDiscoveryBusy = false, printerMessage = "failed") }
                }
        }
    }

    fun selectDiscoveredPrinter(printer: DiscoveredPrinter) {
        saveStarPrinter(printer.identifier, printer.interfaceType)
        _ui.update { it.copy(printerMessage = "selected") }
    }

    fun testPrinter() {
        val state = _ui.value
        viewModelScope.launch {
            val ok = when (state.printerProvider) {
                PrinterProviderType.ESC_POS ->
                    runCatching { printerService.test(state.printerHost, state.printerPort) }.getOrDefault(false)

                PrinterProviderType.STAR ->
                    runCatching { starPrinterService.test(state.starIdentifier, state.starInterface) }.getOrDefault(false)
            }
            _ui.update { it.copy(printerMessage = if (ok) "ok" else "failed") }
        }
    }

    fun openDrawer() {
        val state = _ui.value
        viewModelScope.launch {
            val ok = when (state.printerProvider) {
                PrinterProviderType.ESC_POS ->
                    runCatching { printerService.pulseDrawer(state.printerHost, state.printerPort) }.getOrDefault(false)

                PrinterProviderType.STAR ->
                    runCatching { starPrinterService.pulseDrawer(state.starIdentifier, state.starInterface) }.getOrDefault(false)
            }
            _ui.update { it.copy(printerMessage = if (ok) "drawer_ok" else "failed") }
        }
    }

    fun refresh() {
        val currentToken = token ?: return
        val email = sessionStore.email() ?: "user@cookit.be"
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { refreshLiveData(currentToken, email, primeOrders = true) }
                .onFailure { error -> _ui.update { it.copy(loading = false, online = false, error = readableError(error)) } }
        }
    }

    fun markInboundRead() {
        _ui.update { state -> state.copy(unreadInbound = 0, orders = state.orders.map { it.copy(unread = false) }) }
    }

    fun addProduct(product: Product) {
        if (guardPendingOrderEdit()) return
        val next = addToDraft(_ui.value.draftCart, product)
        updateDraft(cart = next)
    }

    fun incrementProduct(productId: Long) {
        if (guardPendingOrderEdit()) return
        val next = _ui.value.draftCart.map {
            if (it.product.id == productId) it.copy(quantity = it.quantity + 1) else it
        }
        updateDraft(cart = next)
    }

    fun decrementProduct(productId: Long) {
        if (guardPendingOrderEdit()) return
        val next = _ui.value.draftCart.mapNotNull {
            if (it.product.id != productId) it
            else if (it.quantity <= 1) null
            else it.copy(quantity = it.quantity - 1)
        }
        updateDraft(cart = next)
    }

    fun setDraftOrderType(type: OrderType) {
        if (guardPendingOrderEdit()) return
        val tableId = if (type == OrderType.DINE_IN) _ui.value.draftTableId else null
        updateDraft(orderType = type, tableId = tableId)
    }

    fun selectDraftTable(tableId: Long?) {
        if (guardPendingOrderEdit()) return
        updateDraft(tableId = tableId)
    }

    private fun guardPendingOrderEdit(): Boolean {
        val pendingId = _ui.value.pendingRemoteOrderId ?: return false
        _ui.update {
            it.copy(
                paymentSheetOpen = true,
                checkoutError = "La commande #$pendingId existe déjà sur Cookit. Terminez son paiement avant de modifier le panier."
            )
        }
        return true
    }

    fun requestCheckout() {
        val state = _ui.value
        if (state.draftCart.isEmpty()) return
        if (state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        _ui.update {
            it.copy(
                paymentSheetOpen = true,
                checkoutMessage = null,
                checkoutError = null,
                error = null
            )
        }
    }

    fun dismissPaymentSheet() {
        if (_ui.value.checkoutBusy) return
        _ui.update { it.copy(paymentSheetOpen = false, checkoutError = null) }
    }

    fun confirmPayment(method: PosPaymentMethod) {
        val state = _ui.value
        val lines = state.draftCart
        if (lines.isEmpty()) return

        if (state.policy.cashSessionRequired && state.activeCashSession == null) {
            _ui.update {
                it.copy(
                    checkoutMessage = "cash_required",
                    checkoutError = null,
                    paymentSheetOpen = false
                )
            }
            return
        }

        if (state.demoMode) {
            finishSuccessfulCheckout(emptyList())
            return
        }

        val currentToken = token ?: run {
            _ui.update { it.copy(checkoutError = "Session Cookit expirée. Reconnectez-vous.") }
            return
        }

        viewModelScope.launch {
            _ui.update {
                it.copy(
                    checkoutBusy = true,
                    checkoutMessage = null,
                    checkoutError = null,
                    error = null
                )
            }

            runCatching {
                val existingOrderId = _ui.value.pendingRemoteOrderId
                val orderId = if (existingOrderId != null) {
                    existingOrderId
                } else {
                    api.createOrder(
                        currentToken,
                        _ui.value.draftOrderType,
                        _ui.value.draftCart,
                        _ui.value.draftTableId
                    ).also { createdOrderId ->
                        updateDraft(pendingOrderId = createdOrderId)
                        runCatching { api.createKot(currentToken, createdOrderId) }
                    }
                }

                api.payOrder(
                    currentToken,
                    orderId,
                    _ui.value.draftCart.sumOf { it.total },
                    method
                )

                val fresh = api.orders(currentToken)
                knownOrderIds.addAll(fresh.map { it.id })
                finishSuccessfulCheckout(fresh)
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        checkoutBusy = false,
                        checkoutError = readableError(e),
                        paymentSheetOpen = true
                    )
                }
            }
        }
    }

    private fun finishSuccessfulCheckout(freshOrders: List<PosOrder>) {
        draftStore.clear()
        persistedDraft = PersistedDraft()
        _ui.update {
            it.copy(
                checkoutBusy = false,
                checkoutMessage = if (it.demoMode) "demo" else "paid",
                checkoutError = null,
                checkoutNonce = it.checkoutNonce + 1,
                paymentSheetOpen = false,
                draftCart = emptyList(),
                draftOrderType = OrderType.DINE_IN,
                draftTableId = it.tables.firstOrNull { table -> table.available }?.id,
                pendingRemoteOrderId = null,
                orders = if (freshOrders.isEmpty()) it.orders else freshOrders,
                online = true
            )
        }
        persistCurrentDraft()
    }

    private fun updateDraft(
        cart: List<CartLine> = _ui.value.draftCart,
        orderType: OrderType = _ui.value.draftOrderType,
        tableId: Long? = _ui.value.draftTableId,
        pendingOrderId: Long? = _ui.value.pendingRemoteOrderId
    ) {
        _ui.update {
            it.copy(
                draftCart = cart,
                draftOrderType = orderType,
                draftTableId = tableId,
                pendingRemoteOrderId = pendingOrderId,
                checkoutMessage = null,
                checkoutError = null
            )
        }
        persistCurrentDraft()
    }

    private fun persistCurrentDraft() {
        val state = _ui.value
        val draft = PersistedDraft(
            entries = state.draftCart.map { DraftEntry(it.product.id, it.quantity) },
            orderType = state.draftOrderType,
            tableId = state.draftTableId,
            pendingOrderId = state.pendingRemoteOrderId
        )
        persistedDraft = draft
        if (draft.entries.isEmpty() && draft.pendingOrderId == null) {
            draftStore.clear()
        } else {
            draftStore.save(draft)
        }
    }

    private fun addToDraft(cart: List<CartLine>, product: Product): List<CartLine> {
        val existing = cart.firstOrNull { it.product.id == product.id }
        return if (existing == null) cart + CartLine(product, 1)
        else cart.map { if (it.product.id == product.id) it.copy(quantity = it.quantity + 1) else it }
    }

    fun openCashSession(openingAmount: Double) {
        val currentToken = token ?: return
        val register = _ui.value.cashRegisters.firstOrNull() ?: run {
            _ui.update { it.copy(error = "Aucune caisse configurée pour cette branche.") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(cashBusy = true, error = null) }
            runCatching { api.openCashSession(currentToken, register.id, openingAmount) }
                .onSuccess { session -> _ui.update { it.copy(cashBusy = false, activeCashSession = session) } }
                .onFailure { e -> _ui.update { it.copy(cashBusy = false, error = readableError(e)) } }
        }
    }

    private suspend fun bootstrap(email: String) {
        val currentToken = token ?: return
        _ui.update { it.copy(loading = true, error = null) }
        refreshLiveData(currentToken, email, primeOrders = true)
        _ui.update { it.copy(authenticated = true, demoMode = false, loading = false, online = true) }
        startPolling()
    }

    private suspend fun refreshLiveData(currentToken: String, email: String, primeOrders: Boolean) {
        val platform = api.platform(currentToken, email)
        val catalog = api.catalog(currentToken)
        val liveOrders = api.orders(currentToken)
        val tables = runCatching { api.tables(currentToken) }.getOrDefault(emptyList())
        val cashRegisters = runCatching { api.cashRegisters(currentToken) }.getOrDefault(emptyList())
        val denominations = runCatching { api.cashDenominations(currentToken) }.getOrDefault(emptyList())
        val activeCash = runCatching { api.activeCashSession(currentToken) }.getOrNull()

        if (primeOrders) {
            knownOrderIds.clear()
            knownOrderIds.addAll(liveOrders.map { it.id })
        }

        val liveProducts = catalog.products.ifEmpty { DemoRepository.products }
        val savedEntries = if (_ui.value.draftCart.isNotEmpty()) {
            _ui.value.draftCart.map { DraftEntry(it.product.id, it.quantity) }
        } else {
            persistedDraft.entries
        }
        val restoredCart = savedEntries.mapNotNull { entry ->
            liveProducts.firstOrNull { it.id == entry.productId }
                ?.let { CartLine(it, entry.quantity.coerceAtLeast(1)) }
        }
        val requestedTableId = _ui.value.draftTableId ?: persistedDraft.tableId
        val restoredTableId = requestedTableId
            ?.takeIf { id -> tables.any { it.id == id } }
            ?: tables.firstOrNull { it.available }?.id

        _ui.update {
            it.copy(
                authenticated = true,
                online = true,
                loading = false,
                user = platform.user,
                policy = platform.policy,
                categories = catalog.categories.ifEmpty { DemoRepository.categories },
                products = liveProducts,
                orders = liveOrders,
                tables = tables,
                draftCart = restoredCart,
                draftOrderType = if (restoredCart.isNotEmpty()) persistedDraft.orderType else it.draftOrderType,
                draftTableId = restoredTableId,
                pendingRemoteOrderId = persistedDraft.pendingOrderId ?: it.pendingRemoteOrderId,
                cashRegisters = cashRegisters,
                cashDenominations = denominations.ifEmpty { DemoRepository.denominations },
                activeCashSession = activeCash,
                lastSyncEpochMs = System.currentTimeMillis(),
                error = null
            )
        }
        persistCurrentDraft()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2_000)
                val currentToken = token ?: break
                runCatching { api.orders(currentToken) }
                    .onSuccess { fresh ->
                        val newInboundIds = fresh
                            .filter { it.id !in knownOrderIds && !it.channel.equals("POS", ignoreCase = true) }
                            .map { it.id }
                            .toSet()
                        if (newInboundIds.isNotEmpty()) runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 240) }
                        knownOrderIds.addAll(fresh.map { it.id })
                        _ui.update { state ->
                            state.copy(
                                online = true,
                                orders = fresh.map { it.copy(unread = it.id in newInboundIds) },
                                unreadInbound = state.unreadInbound + newInboundIds.size,
                                lastSyncEpochMs = System.currentTimeMillis(),
                                error = null
                            )
                        }
                    }
                    .onFailure { error -> _ui.update { it.copy(online = false, error = readableError(error)) } }
            }
        }
    }

    private fun readableError(error: Throwable): String = when (error) {
        is CookitApiException -> when (error.statusCode) {
            401, 422 -> error.message ?: "Identifiants refusés ou requête invalide."
            403 -> "Accès POS refusé pour ce compte ou ce forfait."
            404 -> error.message ?: "Endpoint Cookit introuvable."
            else -> error.message ?: "Erreur API ${error.statusCode}"
        }
        else -> error.message ?: "Erreur réseau Cookit."
    }

    override fun onCleared() {
        pollingJob?.cancel()
        tone.release()
        super.onCleared()
    }
}
