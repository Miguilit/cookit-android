package be.cookit.pos.android.ui

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import be.cookit.pos.android.data.*
import be.cookit.pos.android.data.fiscal.*
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
    val resumedRemoteOrderId: Long? = null,
    val resumedRemoteOrderTotal: Double? = null,
    val openedOrderCode: String? = null,
    val orderLoadBusy: Boolean = false,
    val orderLoadError: String? = null,
    val kots: List<KotTicket> = emptyList(),
    val kdsBusyKotIds: Set<Long> = emptySet(),
    val kdsError: String? = null,
    val billingSheetOpen: Boolean = false,
    val billingBusy: Boolean = false,
    val billingError: String? = null,
    val billingContext: BillingContext? = null,
    val lastCashChange: Double? = null,
    val fiscalIdentity: FiscalRuntimeIdentity? = null,
    val fiscalLocalDbError: String? = null,
    val fiscalOutboxHealth: FiscalOutboxHealth = FiscalOutboxHealth(),
    val fiscalSyncMessage: String? = null,
    val fdmSettings: FiscalFdmSettings = FiscalFdmSettings(),
    val fdmReadiness: FiscalProviderReadiness = CheckboxFiscalProviderAdapter().readiness(FiscalFdmSettings()),
    val fdmMessage: String? = null,
    val fdmProbeBusy: Boolean = false,
    val fdmProbe: FdmConnectivityProbeResult = FdmConnectivityProbeResult(),
    val fiscalAgentConfigured: Boolean = false,
    val fiscalAgentDeviceHint: String = "",
    val fiscalAgentMessage: String? = null,
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
    private val localDatabase = CookitLocalDatabase.get(application)
    private val fiscalRuntimeRepository = FiscalRuntimeRepository(localDatabase.fiscalRuntimeDao())
    private val fiscalOutboxRepository = FiscalOutboxRepository(localDatabase.fiscalOutboxDao())
    private val fiscalCloudClient = FiscalCloudClient()
    private val fiscalSyncEngine = FiscalSyncEngine(fiscalOutboxRepository, fiscalCloudClient)
    private val fdmSettingsStore = FiscalFdmSettingsStore(application)
    private val fdmProviderAdapter = CheckboxFiscalProviderAdapter()
    private val fdmGraphqlClient = FdmGraphqlClient()
    private val fdmRuntime = FiscalFdmRuntime(fdmProviderAdapter, fdmGraphqlClient)
    private val fdmConnectivityProbe = FdmConnectivityProbe()
    private val fiscalAgentCredentialStore = FiscalAgentCredentialStore(application)
    private val fiscalAgentClient = FiscalAgentClient()
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
            fdmSettings = fdmSettingsStore.load(),
            fdmReadiness = fdmRuntime.readiness(fdmSettingsStore.load()),
            fiscalAgentConfigured = fiscalAgentCredentialStore.configured(),
            fiscalAgentDeviceHint = fiscalAgentCredentialStore.deviceHint(),
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
    private var fiscalSyncJob: Job? = null
    private val knownOrderIds = linkedSetOf<Long>()
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    init {
        api.languageCode = sessionStore.language().code
        viewModelScope.launch { initializeFiscalRuntime() }
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
        fiscalSyncJob?.cancel()
        _ui.update {
            PosUiState(
                authenticated = true,
                demoMode = true,
                online = true,
                language = it.language,
                fiscalIdentity = it.fiscalIdentity,
                fiscalLocalDbError = it.fiscalLocalDbError,
                fiscalOutboxHealth = it.fiscalOutboxHealth,
                fiscalSyncMessage = it.fiscalSyncMessage,
                fdmSettings = it.fdmSettings,
                fdmReadiness = it.fdmReadiness,
                fdmMessage = it.fdmMessage,
                fdmProbe = it.fdmProbe,
                fiscalAgentConfigured = it.fiscalAgentConfigured,
                fiscalAgentDeviceHint = it.fiscalAgentDeviceHint,
                fiscalAgentMessage = it.fiscalAgentMessage,
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
        fiscalSyncJob?.cancel()
        token = null
        knownOrderIds.clear()
        sessionStore.clearSession()
        draftStore.clear()
        persistedDraft = PersistedDraft()
        _ui.update {
            PosUiState(
                language = it.language,
                fiscalIdentity = it.fiscalIdentity,
                fiscalLocalDbError = it.fiscalLocalDbError,
                fiscalOutboxHealth = it.fiscalOutboxHealth,
                fiscalSyncMessage = it.fiscalSyncMessage,
                fdmSettings = it.fdmSettings,
                fdmReadiness = it.fdmReadiness,
                fdmMessage = it.fdmMessage,
                fdmProbe = it.fdmProbe,
                fiscalAgentConfigured = it.fiscalAgentConfigured,
                fiscalAgentDeviceHint = it.fiscalAgentDeviceHint,
                fiscalAgentMessage = it.fiscalAgentMessage,
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

    fun saveFdmSettings(host: String, portText: String) {
        val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 443
        val settings = FiscalFdmSettings(
            provider = FiscalFdmSettings.PROVIDER_CHECKBOX,
            host = host.trim(),
            port = port,
            path = "/graphql",
            useTls = true
        )
        fdmSettingsStore.save(settings)
        _ui.update {
            it.copy(
                fdmSettings = settings,
                fdmReadiness = fdmRuntime.readiness(settings),
                fdmMessage = if (settings.configured) "transport_configured_mapping_gated" else null
            )
        }
    }

    /**
     * Intentionally does not send a GraphQL fiscal mutation. This exposes the permanent provider
     * boundary to the UI while the certified Checkbox signSale input mapping remains unavailable.
     */
    fun verifyFdmAdapterGate() {
        val readiness = fdmRuntime.readiness(_ui.value.fdmSettings)
        _ui.update {
            it.copy(
                fdmReadiness = readiness,
                fdmMessage = if (readiness.readyForFiscalization) "ready" else "mapping_gated"
            )
        }
    }

    fun probeFdmConnectivity() {
        val settings = _ui.value.fdmSettings
        viewModelScope.launch {
            _ui.update { it.copy(fdmProbeBusy = true, fdmMessage = "probe_running") }
            val result = fdmConnectivityProbe.probe(settings)
            _ui.update {
                it.copy(
                    fdmProbeBusy = false,
                    fdmProbe = result,
                    fdmMessage = if (result.transportReady) "probe_reachable" else "probe_failed"
                )
            }
        }
    }

    fun saveFiscalAgentCredentials(deviceId: String, deviceToken: String) {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) {
            _ui.update { it.copy(fiscalAgentMessage = "forbidden") }
            return
        }
        val credentials = FiscalAgentCredentials(deviceId.trim(), deviceToken)
        if (!credentials.configured) {
            _ui.update { it.copy(fiscalAgentMessage = "credentials_incomplete") }
            return
        }
        runCatching { fiscalAgentCredentialStore.save(credentials) }
            .onSuccess {
                _ui.update {
                    it.copy(
                        fiscalAgentConfigured = true,
                        fiscalAgentDeviceHint = credentials.deviceId,
                        fiscalAgentMessage = "credentials_saved"
                    )
                }
            }
            .onFailure { error ->
                _ui.update { it.copy(fiscalAgentMessage = error.message ?: "credentials_failed") }
            }
    }

    fun clearFiscalAgentCredentials() {
        if (!_ui.value.policy.canManageSettings) return
        fiscalAgentCredentialStore.clear()
        _ui.update {
            it.copy(
                fiscalAgentConfigured = false,
                fiscalAgentDeviceHint = "",
                fiscalAgentMessage = "credentials_cleared"
            )
        }
    }

    fun handshakeFiscalAgent() {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) {
            _ui.update { it.copy(fiscalAgentMessage = "forbidden") }
            return
        }
        val credentials = fiscalAgentCredentialStore.load()
        val identity = _ui.value.fiscalIdentity
        if (credentials == null || identity == null) {
            _ui.update { it.copy(fiscalAgentMessage = "credentials_or_identity_missing") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(fiscalAgentMessage = "handshake_running") }
            runCatching {
                fiscalAgentClient.handshake(
                    credentials,
                    fiscalAgentClient.defaultHandshakePayload(identity, _ui.value.fdmSettings)
                )
            }.onSuccess {
                _ui.update { it.copy(fiscalAgentMessage = "handshake_ok") }
            }.onFailure { error ->
                _ui.update { it.copy(fiscalAgentMessage = "handshake_failed:${error.message.orEmpty()}") }
            }
        }
    }

    fun heartbeatFiscalAgent() {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) {
            _ui.update { it.copy(fiscalAgentMessage = "forbidden") }
            return
        }
        val credentials = fiscalAgentCredentialStore.load()
        val identity = _ui.value.fiscalIdentity
        if (credentials == null || identity == null) {
            _ui.update { it.copy(fiscalAgentMessage = "credentials_or_identity_missing") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(fiscalAgentMessage = "heartbeat_running") }
            val payload = org.json.JSONObject()
                .put("runtime_id", identity.runtimeId)
                .put("source_terminal_id", identity.terminalId)
                .put("runtime_type", "android_pos")
                .put("provider", _ui.value.fdmSettings.provider)
            runCatching { fiscalAgentClient.heartbeat(credentials, payload) }
                .onSuccess { _ui.update { it.copy(fiscalAgentMessage = "heartbeat_ok") } }
                .onFailure { error ->
                    _ui.update { it.copy(fiscalAgentMessage = "heartbeat_failed:${error.message.orEmpty()}") }
                }
        }
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
        val state = _ui.value
        if (state.resumedRemoteOrderId != null) {
            _ui.update {
                it.copy(
                    checkoutError = "${state.openedOrderCode ?: "Commande"} est chargée depuis Cookit. Encaissez-la, rafraîchissez-la ou utilisez Nouvelle commande.",
                    paymentSheetOpen = false
                )
            }
            return true
        }

        if (state.pendingRemoteOrderId != null) {
            _ui.update {
                it.copy(
                    checkoutError = "Une commande a déjà été créée sur Cookit et attend la fin de l'encaissement. Réessayez le paiement ou démarrez une nouvelle commande après synchronisation.",
                    paymentSheetOpen = false
                )
            }
            return true
        }
        return false
    }

    fun requestCheckout() {
        val state = _ui.value
        val resumed = state.resumedRemoteOrderId != null
        if (state.draftCart.isEmpty() && (!resumed || (state.resumedRemoteOrderTotal ?: 0.0) <= 0.0)) return
        if (!resumed && state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
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

    fun confirmPayment(method: PosPaymentMethod, tenderedAmount: Double? = null) {
        val state = _ui.value
        val lines = state.draftCart
        val resumed = state.resumedRemoteOrderId != null
        if (lines.isEmpty() && !resumed) return

        val amountDue = if (resumed) {
            state.resumedRemoteOrderTotal?.takeIf { it > 0 } ?: lines.sumOf { it.total }
        } else {
            lines.sumOf { it.total }
        }
        if (amountDue <= 0.0) {
            _ui.update { it.copy(checkoutError = "Montant de la commande indisponible. Rafraîchissez la commande avant l'encaissement.") }
            return
        }
        if (method == PosPaymentMethod.CASH) {
            val tendered = tenderedAmount ?: amountDue
            if (tendered + 0.0001 < amountDue) {
                _ui.update { it.copy(checkoutError = "Montant reçu insuffisant.") }
                return
            }
        }

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
            finishSuccessfulCheckout(emptyList(), cashChange = if (method == PosPaymentMethod.CASH) ((tenderedAmount ?: amountDue) - amountDue).coerceAtLeast(0.0) else null)
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
                val existingOrderId = _ui.value.resumedRemoteOrderId ?: _ui.value.pendingRemoteOrderId
                val orderId = if (existingOrderId != null) {
                    existingOrderId
                } else {
                    try {
                        api.createOrder(
                            currentToken,
                            _ui.value.draftOrderType,
                            _ui.value.draftCart,
                            _ui.value.draftTableId
                        )
                    } catch (e: Throwable) {
                        throw IllegalStateException("Création de la commande — ${readableError(e)}", e)
                    }.also { createdOrderId ->
                        updateDraft(pendingOrderId = createdOrderId)
                    }
                }

                try {
                    api.ensureKot(currentToken, orderId)
                } catch (e: Throwable) {
                    throw IllegalStateException("Envoi en cuisine — ${readableError(e)}", e)
                }

                // Two-phase fiscal guard: durable local evidence MUST exist before the
                // irreversible payment request. The cloud fiscal profile can remain OFF; this
                // local invariant is independent from production FDM activation.
                if (prepareFiscalSaleFromRemote(currentToken, orderId, method.apiValue) == null) {
                    throw IllegalStateException("Préparation fiscale locale — écriture SQLite impossible")
                }

                try {
                    api.payOrder(
                        currentToken,
                        orderId,
                        amountDue,
                        method
                    )
                } catch (e: Throwable) {
                    // Keep the outbox record in PREPARED. On reconnect the reconciliation loop
                    // checks the server settlement state before deciding whether to activate it.
                    throw IllegalStateException("Encaissement — ${readableError(e)}", e)
                }

                activateFiscalSale(orderId)

                val fresh = api.orders(currentToken)
                val freshKots = runCatching { api.kots(currentToken) }.getOrDefault(_ui.value.kots)
                knownOrderIds.addAll(fresh.map { it.id })
                val change = if (method == PosPaymentMethod.CASH) {
                    ((tenderedAmount ?: amountDue) - amountDue).coerceAtLeast(0.0)
                } else null
                finishSuccessfulCheckout(fresh, freshKots, change)
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


    fun sendDraftToKitchen() {
        val state = _ui.value
        if (state.draftCart.isEmpty()) return
        if (state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        if (state.demoMode) {
            draftStore.clear()
            persistedDraft = PersistedDraft()
            _ui.update {
                it.copy(
                    draftCart = emptyList(),
                    pendingRemoteOrderId = null,
                    resumedRemoteOrderId = null,
                    resumedRemoteOrderTotal = null,
                    openedOrderCode = null,
                    checkoutMessage = "sent_to_kitchen"
                )
            }
            return
        }

        val currentToken = token ?: return
        viewModelScope.launch {
            _ui.update { it.copy(checkoutBusy = true, checkoutError = null, error = null) }
            runCatching {
                val orderId = _ui.value.resumedRemoteOrderId ?: _ui.value.pendingRemoteOrderId ?: api.createOrder(
                    currentToken,
                    _ui.value.draftOrderType,
                    _ui.value.draftCart,
                    _ui.value.draftTableId
                ).also { createdId -> updateDraft(pendingOrderId = createdId) }

                api.ensureKot(currentToken, orderId)

                val freshOrders = api.orders(currentToken)
                val freshKots = api.kots(currentToken)
                knownOrderIds.addAll(freshOrders.map { it.id })

                draftStore.clear()
                persistedDraft = PersistedDraft()
                _ui.update {
                    it.copy(
                        checkoutBusy = false,
                        checkoutMessage = "sent_to_kitchen",
                        checkoutError = null,
                        paymentSheetOpen = false,
                        draftCart = emptyList(),
                        draftOrderType = OrderType.DINE_IN,
                        draftTableId = it.tables.firstOrNull { table -> table.available }?.id,
                        pendingRemoteOrderId = null,
                        resumedRemoteOrderId = null,
                        resumedRemoteOrderTotal = null,
                        openedOrderCode = null,
                        orders = freshOrders,
                        kots = freshKots,
                        online = true
                    )
                }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        checkoutBusy = false,
                        checkoutError = "Envoi en cuisine — ${readableError(e)}"
                    )
                }
            }
        }
    }

    fun openOrderForPos(order: PosOrder) {
        if (isTerminalSettlement(order.settlementStatus)) {
            _ui.update { it.copy(orderLoadError = "Cette commande est déjà soldée ou annulée.") }
            return
        }
        val currentToken = token ?: return
        viewModelScope.launch {
            loadRemoteOrderForPos(currentToken, order.id, order.code)
        }
    }

    fun refreshOpenedOrder() {
        val currentToken = token ?: return
        val state = _ui.value
        val orderId = state.resumedRemoteOrderId ?: return
        val code = state.openedOrderCode ?: state.orders.firstOrNull { it.id == orderId }?.code ?: "Commande"
        viewModelScope.launch {
            loadRemoteOrderForPos(currentToken, orderId, code)
        }
    }

    private suspend fun loadRemoteOrderForPos(currentToken: String, orderId: Long, code: String) {
        _ui.update { it.copy(orderLoadBusy = true, orderLoadError = null, checkoutError = null) }
        runCatching { api.orderDraft(currentToken, orderId) }
            .onSuccess { remote ->
                if (isTerminalSettlement(remote.settlementStatus)) {
                    startFreshDraftInternal(message = "remote_settled")
                    val freshOrders = runCatching { api.orders(currentToken) }.getOrDefault(_ui.value.orders)
                    _ui.update { it.copy(orders = freshOrders, orderLoadBusy = false) }
                    return@onSuccess
                }

                val lines = remote.lines.map { line ->
                    val product = _ui.value.products.firstOrNull { it.id == line.menuItemId }
                        ?: Product(
                            id = line.menuItemId,
                            categoryId = 0,
                            name = line.name ?: "Article ${line.menuItemId}",
                            description = "",
                            price = line.price,
                            emoji = "🍽️"
                        )
                    CartLine(
                        product = if (line.price > 0 && product.price != line.price) product.copy(price = line.price) else product,
                        quantity = line.quantity
                    )
                }

                // A reopened server order is not an idempotency-pending local checkout.
                // Do not persist its database id in DraftOrderStore: that was the source of
                // the permanent payment-modal lock after returning to the POS.
                draftStore.clear()
                persistedDraft = PersistedDraft()
                _ui.update {
                    it.copy(
                        orderLoadBusy = false,
                        draftCart = lines,
                        draftOrderType = remote.type,
                        draftTableId = remote.tableId,
                        pendingRemoteOrderId = null,
                        resumedRemoteOrderId = remote.orderId,
                        resumedRemoteOrderTotal = remote.total,
                        openedOrderCode = code,
                        paymentSheetOpen = false,
                        billingSheetOpen = false,
                        billingContext = null,
                        billingError = null,
                        checkoutError = null,
                        checkoutMessage = null,
                        orderLoadError = if (lines.isEmpty() && remote.total <= 0.0) {
                            "La commande ne contient ni lignes ni montant exploitable. Utilisez Rafraîchir après le correctif API."
                        } else null
                    )
                }
            }
            .onFailure { e ->
                _ui.update { it.copy(orderLoadBusy = false, orderLoadError = readableError(e)) }
            }
    }

    fun openBillingTools() {
        val orderId = _ui.value.resumedRemoteOrderId ?: return
        val currentToken = token ?: return
        _ui.update { it.copy(billingSheetOpen = true, billingBusy = true, billingError = null) }
        viewModelScope.launch {
            runCatching { api.billingContext(currentToken, orderId) }
                .onSuccess { context ->
                    _ui.update { it.copy(billingBusy = false, billingContext = context, billingError = null) }
                }
                .onFailure { error ->
                    _ui.update { it.copy(billingBusy = false, billingError = readableError(error)) }
                }
        }
    }

    fun dismissBillingTools() {
        if (_ui.value.billingBusy) return
        _ui.update { it.copy(billingSheetOpen = false, billingError = null) }
    }

    private fun mutateBilling(action: suspend (String, Long) -> BillingContext) {
        val orderId = _ui.value.resumedRemoteOrderId ?: return
        val currentToken = token ?: return
        viewModelScope.launch {
            _ui.update { it.copy(billingBusy = true, billingError = null) }
            runCatching { action(currentToken, orderId) }
                .onSuccess { context ->
                    _ui.update {
                        it.copy(
                            billingBusy = false,
                            billingContext = context,
                            resumedRemoteOrderTotal = context.amountDue.takeIf { due -> due > 0.0 } ?: context.total,
                            billingError = null
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(billingBusy = false, billingError = readableError(error)) }
                }
        }
    }

    fun splitBillEqual(parts: Int) {
        mutateBilling { currentToken, orderId -> api.splitEqual(currentToken, orderId, parts) }
    }

    fun splitBillCustom(amounts: List<Double>) {
        if (amounts.size < 2 || amounts.any { it <= 0.0 }) {
            _ui.update { it.copy(billingError = "Saisissez au moins deux montants positifs.") }
            return
        }
        mutateBilling { currentToken, orderId -> api.splitCustom(currentToken, orderId, amounts) }
    }

    fun splitBillItems(items: Map<Long, Int>) {
        if (items.isEmpty()) {
            _ui.update { it.copy(billingError = "Sélectionnez au moins un article.") }
            return
        }
        mutateBilling { currentToken, orderId -> api.splitItems(currentToken, orderId, items) }
    }

    fun cancelSplitBill() {
        mutateBilling { currentToken, orderId -> api.cancelSplit(currentToken, orderId) }
    }

    fun paySplitBill(billId: Long, method: PosPaymentMethod) {
        val currentToken = token ?: return
        val context = _ui.value.billingContext ?: return
        val bill = context.splitBills.firstOrNull { it.id == billId } ?: return
        if (bill.amountDue <= 0.0) return

        viewModelScope.launch {
            _ui.update { it.copy(billingBusy = true, billingError = null) }
            runCatching {
                if (prepareFiscalSaleFromRemote(currentToken, context.orderId, "split") == null) {
                    throw IllegalStateException("Préparation fiscale locale — écriture SQLite impossible")
                }
                api.paySplitBill(currentToken, billId, bill.amountDue, method)
            }
                .onSuccess { refreshed ->
                    if (refreshed.amountDue <= 0.0001 || isTerminalSettlement(refreshed.settlementStatus)) {
                        activateFiscalSale(context.orderId)
                    }
                    val freshOrders = runCatching { api.orders(currentToken) }.getOrDefault(_ui.value.orders)
                    _ui.update {
                        it.copy(
                            billingBusy = false,
                            billingContext = refreshed,
                            orders = freshOrders,
                            resumedRemoteOrderTotal = refreshed.amountDue.takeIf { due -> due > 0.0 } ?: refreshed.total,
                            billingError = null
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(billingBusy = false, billingError = readableError(error)) }
                }
        }
    }

    fun mergeBillingTables(tableIds: List<Long>) {
        if (tableIds.isEmpty()) {
            _ui.update { it.copy(billingError = "Sélectionnez au moins une table à fusionner.") }
            return
        }
        mutateBilling { currentToken, orderId -> api.mergeTables(currentToken, orderId, tableIds) }
    }

    fun unmergeBillingTables(tableIds: List<Long> = emptyList()) {
        mutateBilling { currentToken, orderId -> api.unmergeTables(currentToken, orderId, tableIds) }
    }

    fun payMergedGroup(method: PosPaymentMethod, tenderedAmount: Double? = null) {
        val state = _ui.value
        val context = state.billingContext ?: return
        val currentToken = token ?: return
        val openOrders = context.groupOrders.filter { it.amountDue > 0.0001 }
        if (openOrders.isEmpty()) return

        val groupDue = openOrders.sumOf { it.amountDue }
        if (method == PosPaymentMethod.CASH) {
            val tendered = tenderedAmount ?: groupDue
            if (tendered + 0.0001 < groupDue) {
                _ui.update { it.copy(billingError = "Montant reçu insuffisant pour le groupe de tables.") }
                return
            }
        }

        if (state.policy.cashSessionRequired && state.activeCashSession == null) {
            _ui.update { it.copy(billingError = "Ouvrez d'abord le fond de caisse.") }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(billingBusy = true, billingError = null) }
            runCatching {
                openOrders.forEach { row ->
                    if (prepareFiscalSaleFromRemote(currentToken, row.orderId, method.apiValue) == null) {
                        throw IllegalStateException("Préparation fiscale locale — écriture SQLite impossible")
                    }
                    api.payOrder(currentToken, row.orderId, row.amountDue, method)
                    activateFiscalSale(row.orderId)
                }
                val freshOrders = api.orders(currentToken)
                val freshKots = runCatching { api.kots(currentToken) }.getOrDefault(_ui.value.kots)
                freshOrders to freshKots
            }.onSuccess { (freshOrders, freshKots) ->
                draftStore.clear()
                persistedDraft = PersistedDraft()
                _ui.update {
                    it.copy(
                        billingBusy = false,
                        billingSheetOpen = false,
                        billingContext = null,
                        billingError = null,
                        orders = freshOrders,
                        kots = freshKots,
                        draftCart = emptyList(),
                        pendingRemoteOrderId = null,
                        resumedRemoteOrderId = null,
                        resumedRemoteOrderTotal = null,
                        openedOrderCode = null,
                        paymentSheetOpen = false,
                        lastCashChange = null
                    )
                }
            }.onFailure { error ->
                _ui.update { it.copy(billingBusy = false, billingError = readableError(error)) }
            }
        }
    }

    fun startNewOrder() {
        val state = _ui.value
        if (state.resumedRemoteOrderId != null) {
            startFreshDraftInternal()
            return
        }

        val pendingId = state.pendingRemoteOrderId
        if (pendingId == null || state.demoMode) {
            startFreshDraftInternal()
            return
        }

        val currentToken = token ?: return
        viewModelScope.launch {
            _ui.update { it.copy(orderLoadBusy = true, checkoutError = null) }
            runCatching { api.orderDraft(currentToken, pendingId) }
                .onSuccess { remote ->
                    if (isTerminalSettlement(remote.settlementStatus)) {
                        startFreshDraftInternal(message = "remote_settled")
                    } else {
                        _ui.update {
                            it.copy(
                                orderLoadBusy = false,
                                paymentSheetOpen = false,
                                checkoutError = "La commande serveur en attente n'est pas encore soldée. Terminez son paiement avant de l'abandonner."
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(orderLoadBusy = false, checkoutError = readableError(e)) }
                }
        }
    }

    private fun startFreshDraftInternal(message: String? = null) {
        draftStore.clear()
        persistedDraft = PersistedDraft()
        _ui.update {
            it.copy(
                orderLoadBusy = false,
                paymentSheetOpen = false,
                draftCart = emptyList(),
                draftOrderType = OrderType.DINE_IN,
                draftTableId = it.tables.firstOrNull { table -> table.available }?.id,
                pendingRemoteOrderId = null,
                resumedRemoteOrderId = null,
                resumedRemoteOrderTotal = null,
                openedOrderCode = null,
                checkoutError = null,
                orderLoadError = null,
                billingSheetOpen = false,
                billingBusy = false,
                billingError = null,
                billingContext = null,
                checkoutMessage = message,
                lastCashChange = null
            )
        }
    }

    private fun isTerminalSettlement(status: String?): Boolean = status
        ?.lowercase()
        ?.let { it in setOf("paid", "settled", "cancelled", "canceled", "refunded", "completed") }
        ?: false


    private fun finishSuccessfulCheckout(freshOrders: List<PosOrder>, freshKots: List<KotTicket> = _ui.value.kots, cashChange: Double? = null) {
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
                resumedRemoteOrderId = null,
                resumedRemoteOrderTotal = null,
                openedOrderCode = null,
                billingSheetOpen = false,
                billingBusy = false,
                billingError = null,
                billingContext = null,
                orders = if (freshOrders.isEmpty()) it.orders else freshOrders,
                kots = freshKots,
                lastCashChange = cashChange,
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

    fun advanceKitchenTicket(ticket: KotTicket) {
        val currentToken = token ?: return
        val nextStatus = when (ticket.status.lowercase()) {
            "pending", "pending_confirmation" -> "in_kitchen"
            "in_kitchen", "cooking" -> "food_ready"
            "food_ready", "ready" -> "served"
            else -> null
        } ?: return

        viewModelScope.launch {
            _ui.update { it.copy(kdsBusyKotIds = it.kdsBusyKotIds + ticket.id, kdsError = null) }
            runCatching {
                api.updateKotStatus(currentToken, ticket.id, nextStatus)
                val freshKots = api.kots(currentToken)
                val freshOrders = api.orders(currentToken)
                freshKots to freshOrders
            }.onSuccess { (freshKots, freshOrders) ->
                knownOrderIds.addAll(freshOrders.map { it.id })
                _ui.update {
                    it.copy(
                        kots = freshKots,
                        orders = freshOrders,
                        kdsBusyKotIds = it.kdsBusyKotIds - ticket.id,
                        kdsError = null,
                        online = true
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        kdsBusyKotIds = it.kdsBusyKotIds - ticket.id,
                        kdsError = readableError(error)
                    )
                }
            }
        }
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

    private suspend fun prepareFiscalSaleFromRemote(
        currentToken: String,
        orderId: Long,
        paymentMethod: String
    ): FiscalOutboxEntity? = runCatching {
        val remote = api.orderDraft(currentToken, orderId)
        val identity = fiscalRuntimeRepository.ensureIdentity()
        fiscalOutboxRepository.prepareRemoteSale(
            identity = identity,
            orderId = orderId,
            orderType = remote.type,
            lines = remote.lines,
            amount = remote.total,
            paymentMethod = paymentMethod
        )
    }.onSuccess {
        refreshFiscalHealth()
    }.onFailure { error ->
        _ui.update {
            it.copy(fiscalLocalDbError = error.message ?: "Préparation fiscale locale impossible")
        }
    }.getOrNull()

    private suspend fun activateFiscalSale(orderId: Long) {
        runCatching {
            val identity = fiscalRuntimeRepository.ensureIdentity()
            fiscalOutboxRepository.activate(identity, orderId)
        }
            .onSuccess {
                refreshFiscalHealth()
                kickFiscalSync()
            }
            .onFailure { error ->
                _ui.update {
                    it.copy(fiscalLocalDbError = error.message ?: "Activation de la fiscal outbox impossible")
                }
            }
    }

    private suspend fun refreshFiscalHealth() {
        runCatching {
            val identity = fiscalRuntimeRepository.ensureIdentity()
            fiscalOutboxRepository.health(identity)
        }
            .onSuccess { health -> _ui.update { it.copy(fiscalOutboxHealth = health) } }
            .onFailure { error ->
                _ui.update {
                    it.copy(fiscalLocalDbError = error.message ?: "Lecture de la fiscal outbox impossible")
                }
            }
    }

    private fun kickFiscalSync() {
        if (token == null || _ui.value.demoMode) return
        startFiscalSync(initialDelayMs = 250L)
    }

    private fun startFiscalSync(initialDelayMs: Long = 1_000L) {
        fiscalSyncJob?.cancel()
        fiscalSyncJob = viewModelScope.launch {
            delay(initialDelayMs)
            while (true) {
                val currentToken = token ?: break
                if (_ui.value.demoMode) break

                runCatching {
                    val identity = fiscalRuntimeRepository.ensureIdentity()
                    fiscalSyncEngine.reconcilePrepared(identity) { orderId ->
                        api.orderDraft(currentToken, orderId)
                    }
                    val result = fiscalSyncEngine.sync(currentToken, identity)
                    refreshFiscalHealth()
                    result
                }.onSuccess { result ->
                    _ui.update { state ->
                        val message = when {
                            result.blockedProfileOff > 0 -> "profile_off"
                            result.failed > 0 -> "retry"
                            result.queued > 0 -> "cloud_queued"
                            else -> state.fiscalSyncMessage
                        }
                        state.copy(fiscalSyncMessage = message)
                    }
                }.onFailure { error ->
                    _ui.update {
                        it.copy(fiscalSyncMessage = "retry", fiscalLocalDbError = it.fiscalLocalDbError ?: error.message)
                    }
                }

                delay(15_000L)
            }
        }
    }

    private suspend fun initializeFiscalRuntime() {
        runCatching { fiscalRuntimeRepository.ensureIdentity() }
            .onSuccess { identity ->
                _ui.update { it.copy(fiscalIdentity = identity, fiscalLocalDbError = null) }
                refreshFiscalHealth()
            }
            .onFailure { error ->
                _ui.update {
                    it.copy(
                        fiscalLocalDbError = error.message ?: "Initialisation SQLite fiscale impossible"
                    )
                }
            }
    }

    private suspend fun bootstrap(email: String) {
        val currentToken = token ?: return
        _ui.update { it.copy(loading = true, error = null) }
        refreshLiveData(currentToken, email, primeOrders = true)
        _ui.update { it.copy(authenticated = true, demoMode = false, loading = false, online = true) }
        refreshFiscalHealth()
        startPolling()
        startFiscalSync()
    }

    private suspend fun refreshLiveData(currentToken: String, email: String, primeOrders: Boolean) {
        val platform = api.platform(currentToken, email)
        val fiscalIdentityResult = runCatching {
            fiscalRuntimeRepository.bindScope(
                restaurantId = platform.user.restaurantId,
                branchId = platform.user.branchId,
                restaurantName = platform.user.restaurant,
                branchName = platform.user.branch
            )
        }
        val catalog = api.catalog(currentToken)
        val liveOrders = api.orders(currentToken)
        val liveKots = runCatching { api.kots(currentToken) }.getOrDefault(emptyList())
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

        val restoredPendingId = persistedDraft.pendingOrderId ?: _ui.value.pendingRemoteOrderId
        val pendingServerOrder = restoredPendingId?.let { id -> liveOrders.firstOrNull { it.id == id } }
        val clearStalePending = pendingServerOrder != null && isTerminalSettlement(pendingServerOrder.settlementStatus)
        if (clearStalePending) {
            draftStore.clear()
            persistedDraft = PersistedDraft()
        }

        _ui.update {
            it.copy(
                authenticated = true,
                online = true,
                loading = false,
                user = platform.user,
                policy = platform.policy,
                fiscalIdentity = fiscalIdentityResult.getOrNull() ?: it.fiscalIdentity,
                fiscalLocalDbError = fiscalIdentityResult.exceptionOrNull()?.message,
                categories = catalog.categories.ifEmpty { DemoRepository.categories },
                products = liveProducts,
                orders = liveOrders,
                kots = liveKots,
                tables = tables,
                draftCart = if (clearStalePending) emptyList() else restoredCart,
                draftOrderType = if (!clearStalePending && restoredCart.isNotEmpty()) persistedDraft.orderType else it.draftOrderType,
                draftTableId = restoredTableId,
                pendingRemoteOrderId = if (clearStalePending) null else restoredPendingId,
                resumedRemoteOrderId = if (clearStalePending) null else it.resumedRemoteOrderId,
                resumedRemoteOrderTotal = if (clearStalePending) null else it.resumedRemoteOrderTotal,
                openedOrderCode = if (clearStalePending) null else it.openedOrderCode,
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
                runCatching {
                    val freshOrders = api.orders(currentToken)
                    val freshKots = runCatching { api.kots(currentToken) }.getOrDefault(_ui.value.kots)
                    freshOrders to freshKots
                }
                    .onSuccess { (fresh, freshKots) ->
                        val newInboundIds = fresh
                            .filter { it.id !in knownOrderIds && !it.channel.equals("POS", ignoreCase = true) }
                            .map { it.id }
                            .toSet()
                        if (newInboundIds.isNotEmpty()) runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 240) }
                        knownOrderIds.addAll(fresh.map { it.id })
                        _ui.update { state ->
                            val resumedSettled = state.resumedRemoteOrderId
                                ?.let { id -> fresh.firstOrNull { it.id == id } }
                                ?.let { isTerminalSettlement(it.settlementStatus) }
                                ?: false
                            state.copy(
                                online = true,
                                orders = fresh.map { it.copy(unread = it.id in newInboundIds) },
                                kots = freshKots,
                                unreadInbound = state.unreadInbound + newInboundIds.size,
                                lastSyncEpochMs = System.currentTimeMillis(),
                                draftCart = if (resumedSettled) emptyList() else state.draftCart,
                                pendingRemoteOrderId = if (resumedSettled) null else state.pendingRemoteOrderId,
                                resumedRemoteOrderId = if (resumedSettled) null else state.resumedRemoteOrderId,
                                resumedRemoteOrderTotal = if (resumedSettled) null else state.resumedRemoteOrderTotal,
                                openedOrderCode = if (resumedSettled) null else state.openedOrderCode,
                                paymentSheetOpen = if (resumedSettled) false else state.paymentSheetOpen,
                                checkoutMessage = if (resumedSettled) "remote_settled" else state.checkoutMessage,
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
        fiscalSyncJob?.cancel()
        tone.release()
        super.onCleared()
    }
}
