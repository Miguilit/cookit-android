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
    val checkoutNonce: Long = 0,
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
    private val printerStore = PrinterSettingsStore(application)
    private val printerService = EscPosPrinterService()
    private val starPrinterService = StarPrinterService(application)
    private val starDiscoveryService = StarDiscoveryService(application)

    private val _ui = MutableStateFlow(
        PosUiState(
            language = sessionStore.language(),
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

    fun checkout(lines: List<CartLine>, type: OrderType, tableId: Long?) {
        if (lines.isEmpty()) return
        val currentToken = token
        if (currentToken == null || _ui.value.demoMode) {
            _ui.update { it.copy(checkoutMessage = "demo", checkoutNonce = it.checkoutNonce + 1) }
            return
        }
        if (_ui.value.policy.cashSessionRequired && _ui.value.activeCashSession == null) {
            _ui.update { it.copy(checkoutMessage = "cash_required") }
            return
        }
        if (type == OrderType.DINE_IN && tableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required") }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(checkoutBusy = true, checkoutMessage = null, error = null) }
            runCatching {
                val orderId = api.createOrder(currentToken, type, lines, tableId)
                runCatching { api.createKot(currentToken, orderId) }
                api.payOrderCash(currentToken, orderId, lines.sumOf { it.total })
                val fresh = api.orders(currentToken)
                knownOrderIds.addAll(fresh.map { it.id })
                _ui.update {
                    it.copy(
                        checkoutBusy = false,
                        checkoutMessage = "paid",
                        checkoutNonce = it.checkoutNonce + 1,
                        orders = fresh,
                        online = true
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(checkoutBusy = false, checkoutMessage = null, error = readableError(e)) }
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

        _ui.update {
            it.copy(
                authenticated = true,
                online = true,
                loading = false,
                user = platform.user,
                policy = platform.policy,
                categories = catalog.categories.ifEmpty { DemoRepository.categories },
                products = catalog.products.ifEmpty { DemoRepository.products },
                orders = liveOrders,
                tables = tables,
                cashRegisters = cashRegisters,
                cashDenominations = denominations.ifEmpty { DemoRepository.denominations },
                activeCashSession = activeCash,
                lastSyncEpochMs = System.currentTimeMillis(),
                error = null
            )
        }
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
