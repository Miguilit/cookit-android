package be.cookit.pos.android.ui

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.data.*
import be.cookit.pos.android.data.fiscal.*
import be.cookit.pos.android.domain.*
import be.cookit.pos.android.service.FiscalAgentServiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

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
    val mockFdmBusy: Boolean = false,
    val mockFdmMessage: String? = null,
    val embeddedMockFdmStatus: EmbeddedMockFdmStatus = EmbeddedMockFdmStatus(),
    val fiscalAgentConfigured: Boolean = false,
    val fiscalAgentDeviceHint: String = "",
    val fiscalAgentMessage: String? = null,
    val fiscalAgentBusy: Boolean = false,
    val fiscalAgentAutoRunning: Boolean = false,
    val fiscalAgentServiceRunning: Boolean = false,
    val fiscalAgentProcessedJobs: Int = 0,
    val fiscalAgentLastJobId: Long? = null,
    val fiscalAgentLastReceipt: String? = null,
    val fiscalAgentHealth: String = FiscalAgentRuntimeState.HEALTH_STOPPED,
    val fiscalAgentServiceStartedEpochMs: Long? = null,
    val fiscalAgentLastLoopTickEpochMs: Long? = null,
    val fiscalAgentLastHeartbeatEpochMs: Long? = null,
    val fiscalAgentLastPollEpochMs: Long? = null,
    val fiscalAgentLastPollAttemptEpochMs: Long? = null,
    val fiscalAgentLastSuccessEpochMs: Long? = null,
    val fiscalAgentLastErrorEpochMs: Long? = null,
    val fiscalAgentLastError: String? = null,
    val fiscalAgentConsecutiveFailures: Int = 0,
    val fiscalAgentWatchdogRestarts: Int = 0,
    val fiscalAgentWakeLockHeld: Boolean = false,
    val fiscalAgentPendingOutcomes: Int = 0,
    val fiscalAgentActiveJobId: Long? = null,
    val fiscalAgentActiveJobPhase: String? = null,
    val fiscalAgentRetryDisposition: String? = null,
    val fiscalAgentRetryAtEpochMs: Long? = null,
    val fiscalAgentRetryAttempt: Int = 0,
    val fiscalAgentTerminalFailures: Int = 0,
    val fiscalAgentLastTerminalJobId: Long? = null,
    val fiscalAgentManualHold: Boolean = false,
    val fiscalAgentDiagnostics: List<FiscalAgentDiagnosticEntity> = emptyList(),
    val fiscalDiagnosticExportMessage: String? = null,
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
    private val offlineBootstrapStore = OfflineBootstrapStore(application)
    private val draftStore = DraftOrderStore(application)
    private val printerStore = PrinterSettingsStore(application)
    private val localDatabase = CookitLocalDatabase.get(application)
    private val fiscalRuntimeRepository = FiscalRuntimeRepository(localDatabase.fiscalRuntimeDao())
    private val fiscalOutboxRepository = FiscalOutboxRepository(localDatabase.fiscalOutboxDao())
    private val fiscalCloudClient = FiscalCloudClient()
    private val fiscalSyncEngine = FiscalSyncEngine(fiscalOutboxRepository, fiscalCloudClient)
    private val fdmSettingsStore = FiscalFdmSettingsStore(application)
    private val embeddedMockFdmServer = EmbeddedMockFdmServer(application)
    private val storedFdmSettings = fdmSettingsStore.load()
    private val initialFdmSettings = if (BuildConfig.ENABLE_MOCK_FDM && storedFdmSettings.isMock) {
        storedFdmSettings.copy(
            host = EmbeddedMockFdmContract.HOST,
            port = EmbeddedMockFdmContract.PORT,
            path = EmbeddedMockFdmContract.PATH,
            useTls = false
        )
    } else storedFdmSettings
    private val fdmGraphqlClient = FdmGraphqlClient()
    private val fdmRuntime = FiscalFdmRuntime(fdmGraphqlClient)
    private val fdmConnectivityProbe = FdmConnectivityProbe()
    private val fiscalAgentCredentialStore = FiscalAgentCredentialStore(application)
    private val fiscalAgentRuntimeStateStore = FiscalAgentRuntimeStateStore(application)
    private val fiscalAgentDiagnosticDao = localDatabase.fiscalAgentDiagnosticDao()
    private val fiscalDiagnosticExporter = FiscalDiagnosticExporter(
        application,
        fiscalAgentDiagnosticDao,
        fiscalRuntimeRepository,
        fiscalAgentRuntimeStateStore,
        fdmSettingsStore
    )
    private val storedFiscalAgentRuntimeState = fiscalAgentRuntimeStateStore.load()
    private val fiscalAgentClient = FiscalAgentClient()
    private val fiscalAgentRunner = FiscalAgentRunner(fiscalAgentClient, fdmRuntime, localDatabase.fiscalAgentOutcomeDao())
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
            fdmSettings = initialFdmSettings,
            fdmReadiness = fdmRuntime.readiness(initialFdmSettings),
            embeddedMockFdmStatus = embeddedMockFdmServer.status(),
            fiscalAgentConfigured = fiscalAgentCredentialStore.configured(),
            fiscalAgentDeviceHint = fiscalAgentCredentialStore.deviceHint(),
            fiscalAgentAutoRunning = storedFiscalAgentRuntimeState.autoEnabled,
            fiscalAgentServiceRunning = storedFiscalAgentRuntimeState.serviceRunning,
            fiscalAgentBusy = storedFiscalAgentRuntimeState.serviceBusy,
            fiscalAgentMessage = storedFiscalAgentRuntimeState.lastMessage,
            fiscalAgentProcessedJobs = storedFiscalAgentRuntimeState.processedJobs,
            fiscalAgentLastJobId = storedFiscalAgentRuntimeState.lastJobId,
            fiscalAgentLastReceipt = storedFiscalAgentRuntimeState.lastReceipt,
            fiscalAgentHealth = storedFiscalAgentRuntimeState.health,
            fiscalAgentServiceStartedEpochMs = storedFiscalAgentRuntimeState.serviceStartedEpochMs,
            fiscalAgentLastLoopTickEpochMs = storedFiscalAgentRuntimeState.lastLoopTickEpochMs,
            fiscalAgentLastHeartbeatEpochMs = storedFiscalAgentRuntimeState.lastHeartbeatEpochMs,
            fiscalAgentLastPollEpochMs = storedFiscalAgentRuntimeState.lastPollEpochMs,
            fiscalAgentLastPollAttemptEpochMs = storedFiscalAgentRuntimeState.lastPollAttemptEpochMs,
            fiscalAgentLastSuccessEpochMs = storedFiscalAgentRuntimeState.lastSuccessEpochMs,
            fiscalAgentLastErrorEpochMs = storedFiscalAgentRuntimeState.lastErrorEpochMs,
            fiscalAgentLastError = storedFiscalAgentRuntimeState.lastError,
            fiscalAgentConsecutiveFailures = storedFiscalAgentRuntimeState.consecutiveFailures,
            fiscalAgentWatchdogRestarts = storedFiscalAgentRuntimeState.watchdogRestarts,
            fiscalAgentWakeLockHeld = storedFiscalAgentRuntimeState.wakeLockHeld,
            fiscalAgentPendingOutcomes = storedFiscalAgentRuntimeState.pendingOutcomeCount,
            fiscalAgentActiveJobId = storedFiscalAgentRuntimeState.activeJobId,
            fiscalAgentActiveJobPhase = storedFiscalAgentRuntimeState.activeJobPhase,
            fiscalAgentRetryDisposition = storedFiscalAgentRuntimeState.retryDisposition,
            fiscalAgentRetryAtEpochMs = storedFiscalAgentRuntimeState.retryAtEpochMs,
            fiscalAgentRetryAttempt = storedFiscalAgentRuntimeState.retryAttempt,
            fiscalAgentTerminalFailures = storedFiscalAgentRuntimeState.terminalFailures,
            fiscalAgentLastTerminalJobId = storedFiscalAgentRuntimeState.lastTerminalJobId,
            fiscalAgentManualHold = storedFiscalAgentRuntimeState.manualHold,
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
    private var fiscalAgentStateMirrorJob: Job? = null
    private val knownOrderIds = linkedSetOf<Long>()
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    init {
        api.languageCode = sessionStore.language().code
        if (BuildConfig.ENABLE_MOCK_FDM) {
            if (initialFdmSettings != storedFdmSettings) fdmSettingsStore.save(initialFdmSettings)
            val embeddedStatus = embeddedMockFdmServer.start()
            _ui.update { it.copy(embeddedMockFdmStatus = embeddedStatus) }
        }
        startFiscalAgentStateMirror()
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
                mockFdmBusy = it.mockFdmBusy,
                mockFdmMessage = it.mockFdmMessage,
                embeddedMockFdmStatus = it.embeddedMockFdmStatus,
                fiscalAgentConfigured = it.fiscalAgentConfigured,
                fiscalAgentDeviceHint = it.fiscalAgentDeviceHint,
                fiscalAgentMessage = it.fiscalAgentMessage,
                fiscalAgentBusy = it.fiscalAgentBusy,
                fiscalAgentAutoRunning = it.fiscalAgentAutoRunning,
                fiscalAgentServiceRunning = it.fiscalAgentServiceRunning,
                fiscalAgentProcessedJobs = it.fiscalAgentProcessedJobs,
                fiscalAgentLastJobId = it.fiscalAgentLastJobId,
                fiscalAgentLastReceipt = it.fiscalAgentLastReceipt,
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
        offlineBootstrapStore.clear()
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
                mockFdmBusy = it.mockFdmBusy,
                mockFdmMessage = it.mockFdmMessage,
                embeddedMockFdmStatus = it.embeddedMockFdmStatus,
                fiscalAgentConfigured = it.fiscalAgentConfigured,
                fiscalAgentDeviceHint = it.fiscalAgentDeviceHint,
                fiscalAgentMessage = it.fiscalAgentMessage,
                fiscalAgentBusy = it.fiscalAgentBusy,
                fiscalAgentAutoRunning = it.fiscalAgentAutoRunning,
                fiscalAgentServiceRunning = it.fiscalAgentServiceRunning,
                fiscalAgentProcessedJobs = it.fiscalAgentProcessedJobs,
                fiscalAgentLastJobId = it.fiscalAgentLastJobId,
                fiscalAgentLastReceipt = it.fiscalAgentLastReceipt,
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

    fun saveFdmSettings(host: String, portText: String, mockMode: Boolean = false) {
        val mockSelected = BuildConfig.ENABLE_MOCK_FDM && mockMode
        val port = if (mockSelected) {
            EmbeddedMockFdmContract.PORT
        } else {
            portText.toIntOrNull()?.coerceIn(1, 65535) ?: 443
        }
        val settings = FiscalFdmSettings(
            provider = if (mockSelected) FiscalFdmSettings.PROVIDER_MOCK else FiscalFdmSettings.PROVIDER_CHECKBOX,
            host = if (mockSelected) EmbeddedMockFdmContract.HOST else host.trim(),
            port = port,
            path = EmbeddedMockFdmContract.PATH,
            useTls = !mockSelected
        )
        val embeddedStatus = if (mockSelected) embeddedMockFdmServer.start() else embeddedMockFdmServer.status()
        fdmSettingsStore.save(settings)
        _ui.update {
            it.copy(
                fdmSettings = settings,
                fdmReadiness = fdmRuntime.readiness(settings),
                fdmMessage = when {
                    !settings.configured -> null
                    settings.isMock && embeddedStatus.running -> "mock_embedded_ready"
                    settings.isMock -> "mock_embedded_failed"
                    else -> "transport_configured_mapping_gated"
                },
                mockFdmMessage = null,
                embeddedMockFdmStatus = embeddedStatus
            )
        }
    }

    fun restartEmbeddedMockFdm() {
        if (!BuildConfig.ENABLE_MOCK_FDM) return
        embeddedMockFdmServer.stop()
        val status = embeddedMockFdmServer.start()
        _ui.update { it.copy(embeddedMockFdmStatus = status, mockFdmMessage = null) }
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

    fun runMockFdmTest(scenario: String) {
        if (!BuildConfig.ENABLE_MOCK_FDM) {
            _ui.update { it.copy(mockFdmMessage = "mock_disabled") }
            return
        }
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) {
            _ui.update { it.copy(mockFdmMessage = "mock_forbidden") }
            return
        }
        val settings = _ui.value.fdmSettings
        if (!settings.isMock || !settings.configured) {
            _ui.update { it.copy(mockFdmMessage = "mock_not_configured") }
            return
        }
        val embeddedStatus = embeddedMockFdmServer.start()
        _ui.update { it.copy(embeddedMockFdmStatus = embeddedStatus) }
        if (!embeddedStatus.running) {
            _ui.update { it.copy(mockFdmMessage = "mock_embedded_unavailable:${embeddedStatus.lastError.orEmpty()}") }
            return
        }
        val identity = _ui.value.fiscalIdentity
        if (identity == null || identity.restaurantId == null || identity.branchId == null) {
            _ui.update { it.copy(mockFdmMessage = "mock_identity_missing") }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(mockFdmBusy = true, mockFdmMessage = "mock_running:$scenario") }
            val event = runCatching { fiscalOutboxRepository.latest(identity) }.getOrNull()
            if (event == null) {
                _ui.update { it.copy(mockFdmBusy = false, mockFdmMessage = "mock_no_event") }
                return@launch
            }

            val calculatedHash = FiscalCanonicalJson.sha256Hex(event.snapshotJson)
            if (!calculatedHash.equals(event.snapshotHash, ignoreCase = true)) {
                _ui.update {
                    it.copy(
                        mockFdmBusy = false,
                        mockFdmMessage = "mock_local_hash_mismatch:${event.localEventId}"
                    )
                }
                return@launch
            }

            runCatching {
                fdmRuntime.submitSale(
                    settings = settings,
                    event = event,
                    headers = mapOf("X-Cookit-Mock-Scenario" to scenario.trim().ifBlank { "success" })
                )
            }.onSuccess { envelope ->
                val sale = envelope.optJSONObject("data")?.optJSONObject("signSale")
                val receipt = sale?.optString("receiptNumber").orEmpty()
                val duplicate = sale?.optBoolean("duplicate", false) ?: false
                _ui.update {
                    it.copy(
                        mockFdmBusy = false,
                        mockFdmMessage = "mock_ok:${event.localEventId}:${receipt.ifBlank { "n/a" }}:$duplicate"
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(
                        mockFdmBusy = false,
                        mockFdmMessage = "mock_failed:${scenario.trim().ifBlank { "success" }}:${error.message.orEmpty().take(180)}"
                    )
                }
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
        FiscalAgentServiceController.stop(getApplication())
        fiscalAgentCredentialStore.clear()
        fiscalAgentRuntimeStateStore.clear()
        _ui.update {
            it.copy(
                fiscalAgentConfigured = false,
                fiscalAgentDeviceHint = "",
                fiscalAgentMessage = "credentials_cleared",
                fiscalAgentBusy = false,
                fiscalAgentAutoRunning = false,
                fiscalAgentServiceRunning = false,
                fiscalAgentProcessedJobs = 0,
                fiscalAgentLastJobId = null,
                fiscalAgentLastReceipt = null,
                fiscalAgentHealth = FiscalAgentRuntimeState.HEALTH_STOPPED,
                fiscalAgentServiceStartedEpochMs = null,
                fiscalAgentLastLoopTickEpochMs = null,
                fiscalAgentLastHeartbeatEpochMs = null,
                fiscalAgentLastPollEpochMs = null,
                fiscalAgentLastPollAttemptEpochMs = null,
                fiscalAgentLastSuccessEpochMs = null,
                fiscalAgentLastErrorEpochMs = null,
                fiscalAgentLastError = null,
                fiscalAgentConsecutiveFailures = 0,
                fiscalAgentWatchdogRestarts = 0,
                fiscalAgentWakeLockHeld = false,
                fiscalAgentPendingOutcomes = 0,
                fiscalAgentActiveJobId = null,
                fiscalAgentActiveJobPhase = null,
                fiscalAgentRetryDisposition = null,
                fiscalAgentRetryAtEpochMs = null,
                fiscalAgentRetryAttempt = 0,
                fiscalAgentTerminalFailures = 0,
                fiscalAgentLastTerminalJobId = null,
                fiscalAgentManualHold = false
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
            val payload = fiscalAgentClient.defaultHeartbeatPayload(identity, _ui.value.fdmSettings)
            runCatching { fiscalAgentClient.heartbeat(credentials, payload) }
                .onSuccess { _ui.update { it.copy(fiscalAgentMessage = "heartbeat_ok") } }
                .onFailure { error ->
                    _ui.update { it.copy(fiscalAgentMessage = "heartbeat_failed:${error.message.orEmpty()}") }
                }
        }
    }

    fun processNextFiscalAgentJob() {
        if (_ui.value.fiscalAgentAutoRunning || _ui.value.fiscalAgentBusy) return
        if (!canRunFiscalAgent()) return
        viewModelScope.launch { processFiscalAgentIteration(auto = false) }
    }

    fun startFiscalAgentAuto() {
        if (_ui.value.fiscalAgentAutoRunning) return
        if (!canRunFiscalAgent()) return

        FiscalAgentServiceController.start(getApplication())
        val persisted = fiscalAgentRuntimeStateStore.load()
        _ui.update {
            it.copy(
                fiscalAgentAutoRunning = true,
                fiscalAgentServiceRunning = persisted.serviceRunning,
                fiscalAgentMessage = "auto_started"
            )
        }
    }

    fun stopFiscalAgentAuto() {
        FiscalAgentServiceController.stop(getApplication())
        _ui.update {
            it.copy(
                fiscalAgentAutoRunning = false,
                fiscalAgentServiceRunning = false,
                fiscalAgentBusy = false,
                fiscalAgentMessage = "auto_stopped"
            )
        }
    }

    fun resumeFiscalAgentProcessing() {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) return
        val before = fiscalAgentRuntimeStateStore.load()
        if (!before.manualHold) return
        fiscalAgentRuntimeStateStore.clearManualHold()
        viewModelScope.launch {
            runCatching {
                FiscalAgentDiagnosticLogger(fiscalAgentDiagnosticDao, fiscalAgentRuntimeStateStore).record(
                    eventType = FiscalAgentDiagnosticLogger.EVENT_MANUAL_RESUME,
                    health = FiscalAgentRuntimeState.HEALTH_STARTING,
                    jobId = before.activeJobId,
                    jobPhase = FiscalAgentRuntimeStateStore.PHASE_MANUAL_HOLD,
                    provider = _ui.value.fdmSettings.provider,
                    runtimeId = _ui.value.fiscalIdentity?.runtimeId,
                    message = "manual_resume"
                )
            }
        }
        if (fiscalAgentRuntimeStateStore.load().autoEnabled) {
            FiscalAgentServiceController.resumeIfEnabled(getApplication())
        }
        _ui.update { it.copy(fiscalAgentMessage = "manual_resume") }
    }

    private fun maybeResumeFiscalAgentAuto() {
        if (!fiscalAgentRuntimeStateStore.load().autoEnabled) return
        if (!_ui.value.authenticated || _ui.value.demoMode) return
        if (!_ui.value.policy.canManageSettings) return
        if (!fiscalAgentCredentialStore.configured()) return
        val identity = _ui.value.fiscalIdentity ?: return
        if (identity.restaurantId == null || identity.branchId == null) return
        if (!canRunFiscalAgent()) return

        FiscalAgentServiceController.resumeIfEnabled(getApplication())
        _ui.update { it.copy(fiscalAgentAutoRunning = true, fiscalAgentMessage = "auto_resumed") }
    }

    private fun startFiscalAgentStateMirror() {
        if (fiscalAgentStateMirrorJob?.isActive == true) return
        fiscalAgentStateMirrorJob = viewModelScope.launch {
            while (isActive) {
                val persisted = fiscalAgentRuntimeStateStore.load()
                val diagnostics = try {
                    fiscalAgentDiagnosticDao.latest(60)
                } catch (_: Throwable) {
                    _ui.value.fiscalAgentDiagnostics
                }
                _ui.update { current ->
                    current.copy(
                        fiscalAgentAutoRunning = persisted.autoEnabled,
                        fiscalAgentServiceRunning = persisted.serviceRunning,
                        fiscalAgentBusy = if (persisted.autoEnabled) persisted.serviceBusy else current.fiscalAgentBusy,
                        fiscalAgentProcessedJobs = persisted.processedJobs,
                        fiscalAgentLastJobId = persisted.lastJobId,
                        fiscalAgentLastReceipt = persisted.lastReceipt,
                        fiscalAgentHealth = persisted.health,
                        fiscalAgentServiceStartedEpochMs = persisted.serviceStartedEpochMs,
                        fiscalAgentLastLoopTickEpochMs = persisted.lastLoopTickEpochMs,
                        fiscalAgentLastHeartbeatEpochMs = persisted.lastHeartbeatEpochMs,
                        fiscalAgentLastPollEpochMs = persisted.lastPollEpochMs,
                        fiscalAgentLastPollAttemptEpochMs = persisted.lastPollAttemptEpochMs,
                        fiscalAgentLastSuccessEpochMs = persisted.lastSuccessEpochMs,
                        fiscalAgentLastErrorEpochMs = persisted.lastErrorEpochMs,
                        fiscalAgentLastError = persisted.lastError,
                        fiscalAgentConsecutiveFailures = persisted.consecutiveFailures,
                        fiscalAgentWatchdogRestarts = persisted.watchdogRestarts,
                        fiscalAgentWakeLockHeld = persisted.wakeLockHeld,
                        fiscalAgentPendingOutcomes = persisted.pendingOutcomeCount,
                        fiscalAgentActiveJobId = persisted.activeJobId,
                        fiscalAgentActiveJobPhase = persisted.activeJobPhase,
                        fiscalAgentRetryDisposition = persisted.retryDisposition,
                        fiscalAgentRetryAtEpochMs = persisted.retryAtEpochMs,
                        fiscalAgentRetryAttempt = persisted.retryAttempt,
                        fiscalAgentTerminalFailures = persisted.terminalFailures,
                        fiscalAgentLastTerminalJobId = persisted.lastTerminalJobId,
                        fiscalAgentManualHold = persisted.manualHold,
                        fiscalAgentDiagnostics = diagnostics,
                        fiscalAgentMessage = if (persisted.autoEnabled || persisted.serviceRunning) {
                            persisted.lastMessage ?: current.fiscalAgentMessage
                        } else {
                            current.fiscalAgentMessage
                        }
                    )
                }
                delay(1_000L)
            }
        }
    }

    fun exportFiscalDiagnostics() {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) {
            _ui.update { it.copy(fiscalDiagnosticExportMessage = "forbidden") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(fiscalDiagnosticExportMessage = "exporting") }
            runCatching {
                withContext(Dispatchers.IO) { fiscalDiagnosticExporter.export() }
            }.onSuccess { file ->
                val app = getApplication<Application>()
                val uri = FileProvider.getUriForFile(
                    app,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, fiscalStrings(_ui.value.language).exportDiagnostic).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(chooser)
                _ui.update { it.copy(fiscalDiagnosticExportMessage = "export_ready") }
            }.onFailure { error ->
                _ui.update { it.copy(fiscalDiagnosticExportMessage = "export_failed:${error.message.orEmpty().take(160)}") }
            }
        }
    }

    fun clearFiscalDiagnostics() {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) return
        viewModelScope.launch {
            runCatching { fiscalAgentDiagnosticDao.clear() }
            _ui.update { it.copy(fiscalAgentDiagnostics = emptyList(), fiscalDiagnosticExportMessage = "history_cleared") }
        }
    }

    private fun canRunFiscalAgent(): Boolean {
        if (!_ui.value.policy.canManageSettings || _ui.value.demoMode) {
            _ui.update { it.copy(fiscalAgentMessage = "forbidden") }
            return false
        }
        val credentials = fiscalAgentCredentialStore.load()
        val identity = _ui.value.fiscalIdentity
        if (credentials == null || identity == null || identity.restaurantId == null || identity.branchId == null) {
            _ui.update { it.copy(fiscalAgentMessage = "credentials_or_identity_missing") }
            return false
        }

        val settings = _ui.value.fdmSettings
        if (settings.isMock) {
            val status = embeddedMockFdmServer.start()
            _ui.update { it.copy(embeddedMockFdmStatus = status) }
            if (!status.running) {
                _ui.update { it.copy(fiscalAgentMessage = "agent_mock_unavailable:${status.lastError.orEmpty()}") }
                return false
            }
        }

        val readiness = fdmRuntime.readiness(settings)
        if (!readiness.readyForFiscalization) {
            _ui.update {
                it.copy(
                    fdmReadiness = readiness,
                    fiscalAgentMessage = "agent_provider_gated:${readiness.reason.orEmpty()}"
                )
            }
            return false
        }
        return true
    }

    private suspend fun processFiscalAgentIteration(auto: Boolean): FiscalAgentRunResult? {
        val credentials = fiscalAgentCredentialStore.load()
        val identity = _ui.value.fiscalIdentity
        if (credentials == null || identity == null) {
            _ui.update {
                it.copy(
                    fiscalAgentBusy = false,
                    fiscalAgentMessage = "credentials_or_identity_missing"
                )
            }
            return null
        }

        _ui.update {
            it.copy(
                fiscalAgentBusy = true,
                fiscalAgentMessage = if (auto) "auto_polling" else "job_polling"
            )
        }

        return runCatchingPreservingCancellation {
            fiscalAgentRunner.processNext(credentials, identity, _ui.value.fdmSettings)
        }.fold(
            onSuccess = { result ->
                if (!result.processed) {
                    _ui.update { it.copy(fiscalAgentBusy = false, fiscalAgentMessage = "job_idle") }
                } else {
                    val persisted = fiscalAgentRuntimeStateStore.recordProcessed(
                        jobId = result.jobId,
                        receiptNumber = result.receiptNumber
                    )
                    _ui.update {
                        it.copy(
                            fiscalAgentBusy = false,
                            fiscalAgentProcessedJobs = persisted.processedJobs,
                            fiscalAgentLastJobId = persisted.lastJobId,
                            fiscalAgentLastReceipt = persisted.lastReceipt,
                            fiscalAgentMessage = "job_ok:${result.jobId}:${result.receiptNumber.orEmpty()}:${result.duplicate}:${result.replayedFromLocalJournal}"
                        )
                    }
                }
                result
            },
            onFailure = { error ->
                _ui.update {
                    it.copy(
                        fiscalAgentBusy = false,
                        fiscalAgentMessage = "job_failed:${error.message.orEmpty().take(220)}"
                    )
                }
                null
            }
        )
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

    fun refreshDashboard() {
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

    fun requestCheckout() {
        val state = _ui.value
        val resumed = state.resumedRemoteOrderId != null
        if (state.draftCart.isEmpty() && (!resumed || (state.resumedRemoteOrderTotal ?: 0.0) <= 0.0)) return
        if (!resumed && state.draftOrderType == OrderType.DINE_IN && state.draftTableId == null) {
            _ui.update { it.copy(checkoutMessage = "table_required", checkoutError = null) }
            return
        }
        if (!resumed && state.draftOrderType == OrderType.DELIVERY && (state.deliveryAddress.isBlank() || state.deliveryExecutiveId == null)) {
            openDeliveryDetails()
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
                            _ui.value.draftTableId,
                            customerName = _ui.value.deliveryCustomerName.takeIf { it.isNotBlank() },
                            customerPhone = _ui.value.deliveryCustomerPhone.takeIf { it.isNotBlank() },
                            deliveryAddress = _ui.value.deliveryAddress.takeIf { it.isNotBlank() },
                            deliveryFee = _ui.value.deliveryFeeText.replace(',', '.').toDoubleOrNull() ?: 0.0,
                            deliveryExecutiveId = _ui.value.deliveryExecutiveId
                        )
                    } catch (e: Throwable) {
                        throw IllegalStateException("Création de la commande — ${readableError(e)}", e)
                    }.also { createdOrderId ->
                        updateDraft(pendingOrderId = createdOrderId)
                    }
                }

                // KOT is deliberately independent from settlement, matching Cookit Cloud.
                // A paid order can receive its KOT later from the Orders screen.

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
                clearDeliveryDraft()
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
        if (state.draftOrderType == OrderType.DELIVERY && (state.deliveryAddress.isBlank() || state.deliveryExecutiveId == null)) {
            openDeliveryDetails()
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
                    _ui.value.draftTableId,
                    customerName = _ui.value.deliveryCustomerName.takeIf { it.isNotBlank() },
                    customerPhone = _ui.value.deliveryCustomerPhone.takeIf { it.isNotBlank() },
                    deliveryAddress = _ui.value.deliveryAddress.takeIf { it.isNotBlank() },
                    deliveryFee = _ui.value.deliveryFeeText.replace(',', '.').toDoubleOrNull() ?: 0.0,
                    deliveryExecutiveId = _ui.value.deliveryExecutiveId
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
                clearDeliveryDraft()
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
    ): FiscalOutboxEntity? = runCatchingPreservingCancellation {
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
        runCatchingPreservingCancellation {
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
        runCatchingPreservingCancellation {
            val identity = fiscalRuntimeRepository.ensureIdentity()
            fiscalOutboxRepository.health(identity)
        }
            .onSuccess { health ->
                _ui.update { it.copy(fiscalOutboxHealth = health, fiscalLocalDbError = null) }
            }
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

                runCatchingPreservingCancellation {
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
                }.onFailure {
                    // Cloud/network/provider failures are sync state, not SQLite failures.
                    _ui.update { it.copy(fiscalSyncMessage = "retry") }
                }

                delay(15_000L)
            }
        }
    }

    private suspend fun initializeFiscalRuntime() {
        runCatchingPreservingCancellation { fiscalRuntimeRepository.ensureIdentity() }
            .onSuccess { identity ->
                _ui.update { it.copy(fiscalIdentity = identity, fiscalLocalDbError = null) }
                refreshFiscalHealth()
                maybeResumeFiscalAgentAuto()
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

        val liveResult = runCatching { refreshLiveData(currentToken, email, primeOrders = true) }
        if (liveResult.isSuccess) {
            _ui.update { it.copy(authenticated = true, demoMode = false, loading = false, online = true) }
            refreshFiscalHealth()
            startPolling()
            startFiscalSync()
            maybeResumeFiscalAgentAuto()
            return
        }

        val error = liveResult.exceptionOrNull() ?: IllegalStateException("Cookit bootstrap failed")
        val restored = restoreOfflineBootstrap(error)
        if (!restored) {
            _ui.update {
                it.copy(
                    authenticated = false,
                    demoMode = false,
                    loading = false,
                    online = false,
                    error = readableError(error)
                )
            }
            return
        }

        // Local runtime remains usable offline. Polling/sync are retry-safe and will recover
        // automatically when Cookit Cloud becomes reachable again.
        refreshFiscalHealth()
        startPolling()
        startFiscalSync()
        maybeResumeFiscalAgentAuto()
    }

    private suspend fun restoreOfflineBootstrap(cause: Throwable): Boolean {
        val cached = offlineBootstrapStore.load() ?: return false
        val fiscalIdentityResult = runCatchingPreservingCancellation {
            fiscalRuntimeRepository.bindScope(
                restaurantId = cached.platform.user.restaurantId,
                branchId = cached.platform.user.branchId,
                restaurantName = cached.platform.user.restaurant,
                branchName = cached.platform.user.branch
            )
        }

        val liveProducts = cached.catalog.products
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
            ?.takeIf { id -> cached.tables.any { it.id == id } }
            ?: cached.tables.firstOrNull { it.available }?.id

        knownOrderIds.clear()
        knownOrderIds.addAll(cached.orders.map { it.id })

        _ui.update { state ->
            state.copy(
                authenticated = true,
                demoMode = false,
                loading = false,
                online = false,
                user = cached.platform.user,
                policy = cached.platform.policy,
                categories = cached.catalog.categories,
                products = cached.catalog.products,
                orders = cached.orders,
                tables = cached.tables,
                draftCart = restoredCart,
                draftOrderType = persistedDraft.orderType,
                draftTableId = restoredTableId,
                pendingRemoteOrderId = persistedDraft.pendingOrderId,
                fiscalIdentity = fiscalIdentityResult.getOrNull() ?: state.fiscalIdentity,
                fiscalLocalDbError = fiscalIdentityResult.exceptionOrNull()?.message ?: state.fiscalLocalDbError,
                lastSyncEpochMs = cached.savedAtEpochMs.takeIf { it > 0L },
                error = "Mode hors ligne — dernier état local chargé. ${readableError(cause)}"
            )
        }
        persistCurrentDraft()
        return true
    }

    private suspend fun refreshLiveData(currentToken: String, email: String, primeOrders: Boolean) {
        val platform = api.platform(currentToken, email)
        val fiscalIdentityResult = runCatchingPreservingCancellation {
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

        runCatching {
            offlineBootstrapStore.save(
                platform = platform,
                catalog = catalog.copy(products = liveProducts),
                orders = liveOrders,
                tables = tables
            )
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
        embeddedMockFdmServer.stop()
        tone.release()
        super.onCleared()
    }
}
