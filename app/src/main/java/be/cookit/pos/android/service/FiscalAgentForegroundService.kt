package be.cookit.pos.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.MainActivity
import be.cookit.pos.android.R
import be.cookit.pos.android.data.fiscal.CookitLocalDatabase
import be.cookit.pos.android.data.fiscal.EmbeddedMockFdmContract
import be.cookit.pos.android.data.fiscal.EmbeddedMockFdmServer
import be.cookit.pos.android.data.fiscal.FdmGraphqlClient
import be.cookit.pos.android.data.fiscal.FdmGraphqlException
import be.cookit.pos.android.data.fiscal.FdmConnectivityProbe
import be.cookit.pos.android.data.fiscal.FiscalAgentClient
import be.cookit.pos.android.data.fiscal.FiscalAgentCredentialStore
import be.cookit.pos.android.data.fiscal.FiscalAgentDiagnosticLogger
import be.cookit.pos.android.data.fiscal.FiscalAgentException
import be.cookit.pos.android.data.fiscal.FiscalAgentIntegrityException
import be.cookit.pos.android.data.fiscal.FiscalAgentJobExecutionException
import be.cookit.pos.android.data.fiscal.FiscalAgentRetryPolicy
import be.cookit.pos.android.data.fiscal.FiscalRetryDisposition
import be.cookit.pos.android.data.fiscal.FiscalAgentOutcomeDao
import be.cookit.pos.android.data.fiscal.FiscalAgentRunner
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeState
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeStateStore
import be.cookit.pos.android.data.fiscal.FiscalAgentTransportException
import be.cookit.pos.android.data.fiscal.FiscalFdmRuntime
import be.cookit.pos.android.data.fiscal.FiscalFdmSettings
import be.cookit.pos.android.data.fiscal.FiscalFdmSettingsStore
import be.cookit.pos.android.data.fiscal.FiscalProviderMappingUnavailable
import be.cookit.pos.android.data.fiscal.FiscalRuntimeRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * C5.1 device-level Fiscal Agent runtime.
 *
 * C4 established autonomous foreground execution, screen-off CPU continuity and boot recovery.
 * C5.1 adds a persistent health ledger plus an independent watchdog that can recover a stalled
 * polling coroutine without binding the fiscal runtime back to the POS UI lifecycle.
 *
 * The real Checkbox/Eutronix adapter remains fail-closed until the certified mapping is installed.
 */
class FiscalAgentForegroundService : Service() {
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, error ->
        // A background fiscal coroutine must never terminate the POS process. Persist the failure,
        // disable automatic processing and leave the UI available for diagnosis/recovery.
        runCatching {
            if (::stateStore.isInitialized) {
                val message = error.message.orEmpty().ifBlank { error::class.java.simpleName }.take(240)
                stateStore.setAutoEnabled(false)
                stateStore.recordFailure(
                    FiscalAgentRuntimeState.HEALTH_DEGRADED,
                    "service_uncaught:$message"
                )
                stateStore.setServiceStatus(
                    running = true,
                    busy = false,
                    message = "service_uncaught_stopped"
                )
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionHandler)
    private var loopJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastLoopProgressElapsedMs: Long = 0L

    private lateinit var stateStore: FiscalAgentRuntimeStateStore
    private lateinit var credentialStore: FiscalAgentCredentialStore
    private lateinit var diagnosticLogger: FiscalAgentDiagnosticLogger
    private lateinit var settingsStore: FiscalFdmSettingsStore
    private lateinit var runtimeRepository: FiscalRuntimeRepository
    private lateinit var client: FiscalAgentClient
    private lateinit var runner: FiscalAgentRunner
    private val retryPolicy = FiscalAgentRetryPolicy()
    private lateinit var outcomeDao: FiscalAgentOutcomeDao
    private lateinit var fdmRuntime: FiscalFdmRuntime
    private lateinit var fdmProbe: FdmConnectivityProbe
    private lateinit var embeddedMock: EmbeddedMockFdmServer
    private lateinit var notifications: NotificationManager
    private lateinit var powerManager: PowerManager
    private var screenOffWakeLock: PowerManager.WakeLock? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> acquireScreenOffWakeLock()
                Intent.ACTION_SCREEN_ON -> releaseScreenOffWakeLock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val database = CookitLocalDatabase.get(this)
        stateStore = FiscalAgentRuntimeStateStore(this)
        credentialStore = FiscalAgentCredentialStore(this)
        diagnosticLogger = FiscalAgentDiagnosticLogger(database.fiscalAgentDiagnosticDao(), stateStore)
        settingsStore = FiscalFdmSettingsStore(this)
        runtimeRepository = FiscalRuntimeRepository(database.fiscalRuntimeDao())
        client = FiscalAgentClient()
        fdmRuntime = FiscalFdmRuntime(FdmGraphqlClient())
        fdmProbe = FdmConnectivityProbe()
        outcomeDao = database.fiscalAgentOutcomeDao()
        runner = FiscalAgentRunner(client, fdmRuntime, outcomeDao)
        embeddedMock = EmbeddedMockFdmServer(this)
        notifications = getSystemService(NotificationManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (!powerManager.isInteractive) {
            acquireScreenOffWakeLock()
        }

        createNotificationChannel()
        startForegroundCompat(buildNotification("STARTING • démarrage du Fiscal Agent"))
        markLoopProgress()
        stateStore.markServiceStarted(System.currentTimeMillis())
        stateStore.setServiceStatus(
            running = true,
            busy = false,
            message = "service_starting"
        )
        scope.launch {
            diagnosticLogger.record(
                eventType = FiscalAgentDiagnosticLogger.EVENT_SERVICE_STARTED,
                health = FiscalAgentRuntimeState.HEALTH_STARTING,
                message = "service_starting"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stateStore.setAutoEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!stateStore.load().autoEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureLoop()
        ensureWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        releaseScreenOffWakeLock()
        watchdogJob?.cancel()
        watchdogJob = null
        loopJob?.cancel()
        loopJob = null
        stateStore.setServiceStatus(
            running = false,
            busy = false,
            message = if (stateStore.load().autoEnabled) "service_restarting" else "auto_stopped"
        )
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureLoop() {
        if (!powerManager.isInteractive) {
            acquireScreenOffWakeLock()
        }
        if (loopJob?.isActive == true) return
        markLoopProgress()
        loopJob = scope.launch { runLoop() }
    }

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (currentCoroutineContext().isActive && stateStore.load().autoEnabled) {
                delay(WATCHDOG_CHECK_MS)
                if (!stateStore.load().autoEnabled) break

                val ageMs = SystemClock.elapsedRealtime() - lastLoopProgressElapsedMs
                if (lastLoopProgressElapsedMs > 0L && ageMs > WATCHDOG_STALL_MS) {
                    val now = System.currentTimeMillis()
                    val runtimeState = stateStore.load()

                    if (runtimeState.serviceBusy) {
                        // Fail-safe rule: never start a second fiscal loop while a provider/cloud
                        // operation may still be in flight. That could create a duplicate FDM call.
                        stateStore.recordFailure(
                            FiscalAgentRuntimeState.HEALTH_DEGRADED,
                            "watchdog_busy_stall_no_parallel_restart",
                            now
                        )
                        stateStore.setServiceStatus(true, true, "watchdog_busy_stall")
                        diagnosticLogger.record(
                            eventType = FiscalAgentDiagnosticLogger.EVENT_WATCHDOG,
                            health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                            jobId = runtimeState.activeJobId,
                            jobPhase = runtimeState.activeJobPhase,
                            message = "watchdog_busy_stall_no_parallel_restart"
                        )
                        updateNotification("DEGRADED • opération en cours, aucun retry parallèle")
                        markLoopProgress() // rate-limit the alert while the bounded network call unwinds.
                        continue
                    }

                    stateStore.recordWatchdogRestart(now)
                    diagnosticLogger.record(
                        eventType = FiscalAgentDiagnosticLogger.EVENT_WATCHDOG,
                        health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                        message = "watchdog_restart",
                        now = now
                    )
                    updateNotification("DEGRADED • watchdog relance la boucle fiscale idle")
                    loopJob?.cancel()
                    loopJob = null
                    markLoopProgress()
                    delay(WATCHDOG_RESTART_GRACE_MS)
                    if (stateStore.load().autoEnabled) {
                        loopJob = scope.launch { runLoop() }
                    }
                }
            }
        }
    }

    private fun acquireScreenOffWakeLock() {
        val lock = screenOffWakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:fiscal_agent_screen_off"
        ).apply {
            setReferenceCounted(false)
            screenOffWakeLock = this
        }

        if (!lock.isHeld) {
            lock.acquire()
            stateStore.setWakeLockHeld(true)
            stateStore.setServiceStatus(
                running = true,
                busy = stateStore.load().serviceBusy,
                message = "screen_off_wakelock_acquired"
            )
        }
    }

    private fun releaseScreenOffWakeLock() {
        screenOffWakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        if (::stateStore.isInitialized) stateStore.setWakeLockHeld(false)
    }

    private suspend fun runLoop() {
        stateStore.setServiceStatus(true, false, "service_running")
        updateNotification("STARTING • initialisation")
        markLoopProgress()

        var heartbeatAt = 0L
        while (currentCoroutineContext().isActive && stateStore.load().autoEnabled) {
            markLoopProgress()
            refreshPendingOutcomeCount()

            val credentials = credentialStore.load()
            val identity = runtimeRepository.currentIdentity()
            val settings = normalizedSettings(settingsStore.load())

            if (credentials == null || identity == null || identity.restaurantId == null || identity.branchId == null) {
                stateStore.recordFailure(
                    FiscalAgentRuntimeState.HEALTH_CONFIG_ERROR,
                    "credentials_or_identity_missing"
                )
                diagnosticLogger.record(
                    eventType = FiscalAgentDiagnosticLogger.EVENT_HEALTH_FAILURE,
                    health = FiscalAgentRuntimeState.HEALTH_CONFIG_ERROR,
                    runtimeId = identity?.runtimeId,
                    message = "credentials_or_identity_missing"
                )
                publishIdle("credentials_or_identity_missing", "CONFIG ERROR • identité/credentials manquants")
                delay(RETRY_NOT_READY_MS)
                continue
            }

            if (settings.isMock) {
                val status = embeddedMock.start()
                if (!status.running) {
                    val error = status.lastError.orEmpty().ifBlank { "mock_fdm_unavailable" }
                    stateStore.recordFailure(FiscalAgentRuntimeState.HEALTH_FDM_ERROR, error)
                    diagnosticLogger.record(
                        eventType = FiscalAgentDiagnosticLogger.EVENT_HEALTH_FAILURE,
                        health = FiscalAgentRuntimeState.HEALTH_FDM_ERROR,
                        provider = settings.provider,
                        runtimeId = identity.runtimeId,
                        message = error
                    )
                    publishIdle(
                        "agent_mock_unavailable:$error",
                        "FDM ERROR • Mock indisponible"
                    )
                    delay(RETRY_NOT_READY_MS)
                    continue
                }
            }

            val readiness = fdmRuntime.readiness(settings)
            if (!readiness.readyForFiscalization) {
                val error = readiness.reason.orEmpty().ifBlank { "provider_not_ready" }
                // A deliberately gated provider (for example Module2 before signSale mapping) is
                // configuration state, not a recoverable runtime outage. Auto-processing must stop
                // instead of spinning forever or touching Cloud jobs while the adapter is disabled.
                stateStore.setAutoEnabled(false)
                stateStore.recordFailure(FiscalAgentRuntimeState.HEALTH_CONFIG_ERROR, error)
                diagnosticLogger.record(
                    eventType = FiscalAgentDiagnosticLogger.EVENT_HEALTH_FAILURE,
                    health = FiscalAgentRuntimeState.HEALTH_CONFIG_ERROR,
                    provider = settings.provider,
                    runtimeId = identity.runtimeId,
                    message = error
                )
                stateStore.setServiceStatus(true, false, "provider_gated_auto_stopped")
                updateNotification("CONFIG • adapter fiscal non activé, auto arrêté")
                stopSelf()
                return
            }

            if (settings.isMock) {
                var probe = fdmProbe.probe(settings)
                if (!probe.transportReady || !probe.graphqlResponded) {
                    // The embedded Mock is process-global. Heal a stale loopback listener before
                    // claiming any Cloud job so a local harness issue cannot consume a lease.
                    embeddedMock.stop()
                    delay(MOCK_RESTART_GRACE_MS)
                    embeddedMock.start()
                    probe = fdmProbe.probe(settings)
                }
                if (!probe.transportReady || !probe.graphqlResponded) {
                    val error = "mock_preflight:${probe.message.orEmpty()}"
                    stateStore.recordFailure(FiscalAgentRuntimeState.HEALTH_FDM_ERROR, error)
                    diagnosticLogger.record(
                        eventType = FiscalAgentDiagnosticLogger.EVENT_HEALTH_FAILURE,
                        health = FiscalAgentRuntimeState.HEALTH_FDM_ERROR,
                        provider = settings.provider,
                        runtimeId = identity.runtimeId,
                        message = error
                    )
                    publishIdle(error, "FDM ERROR • Mock local non joignable")
                    delay(RETRY_NOT_READY_MS)
                    continue
                }
            }

            val now = System.currentTimeMillis()
            if (now - heartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                try {
                    markLoopProgress()
                    client.heartbeat(
                        credentials,
                        client.defaultHeartbeatPayload(identity, settings)
                    )
                    heartbeatAt = now
                    stateStore.recordHeartbeat(now)
                    markLoopProgress()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    stateStore.recordFailure(
                        FiscalAgentRuntimeState.HEALTH_OFFLINE,
                        "heartbeat:${error.message.orEmpty()}"
                    )
                    stateStore.setServiceStatus(
                        running = true,
                        busy = false,
                        message = "heartbeat_failed:${error.message.orEmpty().take(180)}"
                    )
                    diagnosticLogger.record(
                        eventType = FiscalAgentDiagnosticLogger.EVENT_HEALTH_FAILURE,
                        health = FiscalAgentRuntimeState.HEALTH_OFFLINE,
                        provider = settings.provider,
                        runtimeId = identity.runtimeId,
                        message = error.message.orEmpty(),
                        errorClass = error::class.java.simpleName
                    )
                    updateNotification("OFFLINE • heartbeat Cloud en échec")
                    markLoopProgress()
                }
            }

            val retryState = stateStore.load()
            if (retryState.manualHold) {
                stateStore.setServiceStatus(true, false, "manual_hold")
                updateNotification("DEGRADED • MANUAL_HOLD • Job #${retryState.activeJobId ?: "?"}")
                markLoopProgress()
                delay(MANUAL_HOLD_POLL_MS)
                continue
            }

            val retryAt = retryState.retryAtEpochMs
            if (retryAt != null && System.currentTimeMillis() < retryAt) {
                val waitMs = (retryAt - System.currentTimeMillis()).coerceAtLeast(0L)
                stateStore.setServiceStatus(true, false, "retry_wait:${retryState.retryDisposition.orEmpty()}:$retryAt")
                updateNotification("${retryState.health} • ${retryState.activeJobPhase ?: "RETRY_WAIT"} • ${waitMs / 1000L}s")
                markLoopProgress()
                delay(waitMs.coerceAtMost(RETRY_WAIT_TICK_MS).coerceAtLeast(250L))
                continue
            }

            stateStore.setServiceStatus(true, true, "auto_polling")
            stateStore.recordPollAttempt(System.currentTimeMillis())
            updateNotification("${stateStore.load().health} • recherche d’un job")
            markLoopProgress()

            val progress: (Long, String) -> Unit = { jobId, phase ->
                stateStore.setActiveJob(jobId, phase)
                stateStore.setServiceStatus(true, true, "job_phase:$jobId:$phase")
                scope.launch {
                    diagnosticLogger.record(
                        eventType = FiscalAgentDiagnosticLogger.EVENT_JOB_PHASE,
                        health = stateStore.load().health,
                        jobId = jobId,
                        jobPhase = phase,
                        provider = settings.provider,
                        runtimeId = identity.runtimeId,
                        message = phase
                    )
                }
                updateNotification("${stateStore.load().health} • Job #$jobId • $phase")
                markLoopProgress()
            }

            val result = try {
                val pending = runner.flushPendingOutcome(credentials, identity, progress)
                if (pending.processed) pending else runner.processNext(credentials, identity, settings, progress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val failure = error as? FiscalAgentJobExecutionException
                if (failure != null) {
                    val rootError = failure.cause ?: failure
                    val health = classifyFailure(rootError)
                    val decision = retryPolicy.decide(failure)
                    when (decision.disposition) {
                        FiscalRetryDisposition.TERMINAL_FAILURE -> {
                            stateStore.setActiveJob(failure.jobId, FiscalAgentRuntimeStateStore.PHASE_TERMINALIZING)
                            stateStore.setServiceStatus(true, true, "terminalizing:${failure.jobId}")
                            diagnosticLogger.record(
                                eventType = FiscalAgentDiagnosticLogger.EVENT_RETRY_DECISION,
                                health = health,
                                jobId = failure.jobId,
                                jobPhase = FiscalAgentRuntimeStateStore.PHASE_TERMINALIZING,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = "${decision.failureClass.name}:${decision.reasonCode}",
                                errorClass = rootError::class.java.simpleName,
                                retryCount = failure.attempts
                            )
                            try {
                                client.acknowledge(
                                    credentials = credentials,
                                    transactionId = failure.jobId,
                                    identity = identity,
                                    success = false,
                                    error = decision.errorForCloud,
                                    failureClass = decision.failureClass.name,
                                    retryDisposition = decision.disposition.name
                                )
                                stateStore.recordTerminalFailure(failure.jobId, decision.errorForCloud)
                                stateStore.setServiceStatus(true, false, "terminal_failed:${failure.jobId}")
                                diagnosticLogger.record(
                                    eventType = FiscalAgentDiagnosticLogger.EVENT_JOB_TERMINAL_FAILED,
                                    health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                                    jobId = failure.jobId,
                                    jobPhase = "FAILED",
                                    provider = settings.provider,
                                    runtimeId = identity.runtimeId,
                                    message = "${decision.failureClass.name}:${decision.reasonCode}",
                                    errorClass = rootError::class.java.simpleName,
                                    retryCount = failure.attempts
                                )
                                refreshPendingOutcomeCount()
                                updateNotification("DEGRADED • Job #${failure.jobId} • FAILED")
                                markLoopProgress()
                                delay(PROCESSED_POLL_MS)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (terminalizeError: Throwable) {
                                val holdError = "terminalization_failed:${terminalizeError.message.orEmpty()}"
                                stateStore.setManualHold(
                                    jobId = failure.jobId,
                                    disposition = FiscalRetryDisposition.MANUAL_HOLD.name,
                                    attempt = failure.attempts,
                                    error = holdError
                                )
                                stateStore.setServiceStatus(true, false, "manual_hold:${failure.jobId}")
                                diagnosticLogger.record(
                                    eventType = FiscalAgentDiagnosticLogger.EVENT_MANUAL_HOLD,
                                    health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                                    jobId = failure.jobId,
                                    jobPhase = FiscalAgentRuntimeStateStore.PHASE_MANUAL_HOLD,
                                    provider = settings.provider,
                                    runtimeId = identity.runtimeId,
                                    message = holdError,
                                    errorClass = terminalizeError::class.java.simpleName
                                )
                                updateNotification("DEGRADED • MANUAL_HOLD • Job #${failure.jobId}")
                                markLoopProgress()
                            }
                        }

                        FiscalRetryDisposition.RETRY_PROVIDER,
                        FiscalRetryDisposition.RETRY_CLOUD_SYNC -> {
                            val phase = if (decision.disposition == FiscalRetryDisposition.RETRY_PROVIDER) {
                                FiscalAgentRuntimeStateStore.PHASE_RETRY_WAIT
                            } else {
                                FiscalAgentRuntimeStateStore.PHASE_SYNC_RETRY
                            }
                            stateStore.recordRetryDecision(
                                jobId = failure.jobId,
                                phase = phase,
                                disposition = decision.disposition.name,
                                attempt = failure.attempts,
                                retryAtEpochMs = decision.retryAtEpochMs,
                                health = health,
                                error = decision.errorForCloud
                            )
                            stateStore.setServiceStatus(true, false, "retry_scheduled:${failure.jobId}:${decision.disposition.name}")
                            diagnosticLogger.record(
                                eventType = FiscalAgentDiagnosticLogger.EVENT_RETRY_SCHEDULED,
                                health = health,
                                jobId = failure.jobId,
                                jobPhase = phase,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = "${decision.failureClass.name}:${decision.reasonCode}",
                                errorClass = rootError::class.java.simpleName,
                                retryCount = failure.attempts
                            )
                            refreshPendingOutcomeCount()
                            updateNotification("$health • Job #${failure.jobId} • $phase")
                            markLoopProgress()
                        }

                        FiscalRetryDisposition.MANUAL_HOLD -> {
                            stateStore.setManualHold(
                                jobId = failure.jobId,
                                disposition = decision.disposition.name,
                                attempt = failure.attempts,
                                error = decision.errorForCloud
                            )
                            stateStore.setServiceStatus(true, false, "manual_hold:${failure.jobId}")
                            diagnosticLogger.record(
                                eventType = if (decision.reasonCode == "provider_retry_exhausted") {
                                    FiscalAgentDiagnosticLogger.EVENT_RETRY_EXHAUSTED
                                } else {
                                    FiscalAgentDiagnosticLogger.EVENT_MANUAL_HOLD
                                },
                                health = FiscalAgentRuntimeState.HEALTH_DEGRADED,
                                jobId = failure.jobId,
                                jobPhase = FiscalAgentRuntimeStateStore.PHASE_MANUAL_HOLD,
                                provider = settings.provider,
                                runtimeId = identity.runtimeId,
                                message = "${decision.failureClass.name}:${decision.reasonCode}",
                                errorClass = rootError::class.java.simpleName,
                                retryCount = failure.attempts
                            )
                            refreshPendingOutcomeCount()
                            updateNotification("DEGRADED • MANUAL_HOLD • Job #${failure.jobId}")
                            markLoopProgress()
                        }
                    }
                    continue
                }

                val health = classifyFailure(error)
                val runtimeState = stateStore.load()
                if (runtimeState.activeJobId != null) {
                    stateStore.markActiveJobError(health, "job:${error.message.orEmpty()}")
                } else {
                    stateStore.recordFailure(health, "job:${error.message.orEmpty()}")
                }
                stateStore.setServiceStatus(
                    running = true,
                    busy = false,
                    message = "job_failed:${error.message.orEmpty().take(220)}"
                )
                diagnosticLogger.record(
                    eventType = FiscalAgentDiagnosticLogger.EVENT_HEALTH_FAILURE,
                    health = health,
                    jobId = runtimeState.activeJobId,
                    jobPhase = FiscalAgentRuntimeStateStore.PHASE_ERROR,
                    provider = settings.provider,
                    runtimeId = identity.runtimeId,
                    message = error.message.orEmpty(),
                    errorClass = error::class.java.simpleName
                )
                refreshPendingOutcomeCount()
                updateNotification("$health • fiscal processing error")
                markLoopProgress()
                delay(ERROR_RETRY_MS)
                continue
            }

            val completedAt = System.currentTimeMillis()
            stateStore.recordPoll(completedAt)
            refreshPendingOutcomeCount()
            markLoopProgress()

            if (!result.processed) {
                val beforeHealthy = stateStore.load()
                stateStore.recordHealthy(completedAt, clearError = false)
                val afterHealthy = stateStore.load()
                if (beforeHealthy.health != FiscalAgentRuntimeState.HEALTH_CONNECTED &&
                    afterHealthy.health == FiscalAgentRuntimeState.HEALTH_CONNECTED
                ) {
                    diagnosticLogger.record(
                        eventType = FiscalAgentDiagnosticLogger.EVENT_RECOVERED,
                        health = FiscalAgentRuntimeState.HEALTH_CONNECTED,
                        provider = settings.provider,
                        runtimeId = identity.runtimeId,
                        message = beforeHealthy.health,
                        now = completedAt
                    )
                }
                stateStore.setServiceStatus(true, false, "job_idle")
                updateNotification("CONNECTED • aucun job en attente")
                delay(IDLE_POLL_MS)
                continue
            }

            val persisted = stateStore.recordProcessed(
                jobId = result.jobId,
                receiptNumber = result.receiptNumber
            )
            stateStore.clearActiveJobAfterSuccess(completedAt)
            stateStore.setServiceStatus(
                running = true,
                busy = false,
                message = "job_ok:${result.jobId}:${result.receiptNumber.orEmpty()}:${result.duplicate}:${result.replayedFromLocalJournal}"
            )
            diagnosticLogger.record(
                eventType = FiscalAgentDiagnosticLogger.EVENT_JOB_COMPLETED,
                health = FiscalAgentRuntimeState.HEALTH_CONNECTED,
                jobId = result.jobId,
                jobPhase = "FISCALIZED",
                provider = settings.provider,
                runtimeId = identity.runtimeId,
                message = result.receiptNumber,
                now = completedAt
            )
            updateNotification(
                buildString {
                    append("CONNECTED • Job #${result.jobId ?: "?"} traité")
                    persisted.lastReceipt?.let { append(" • $it") }
                }
            )
            delay(PROCESSED_POLL_MS)
        }

        stateStore.setServiceStatus(false, false, "auto_stopped")
        stopSelf()
    }

    private fun classifyFailure(error: Throwable): String = when (error) {
        is FdmGraphqlException,
        is FiscalProviderMappingUnavailable -> FiscalAgentRuntimeState.HEALTH_FDM_ERROR
        is FiscalAgentTransportException,
        is FiscalAgentException -> FiscalAgentRuntimeState.HEALTH_OFFLINE
        is FiscalAgentIntegrityException -> FiscalAgentRuntimeState.HEALTH_DEGRADED
        is IOException -> {
            if (stateStore.load().activeJobPhase == FiscalAgentRuntimeStateStore.PHASE_FDM_CALL) {
                FiscalAgentRuntimeState.HEALTH_FDM_ERROR
            } else {
                FiscalAgentRuntimeState.HEALTH_DEGRADED
            }
        }
        else -> FiscalAgentRuntimeState.HEALTH_DEGRADED
    }

    private fun markLoopProgress() {
        lastLoopProgressElapsedMs = SystemClock.elapsedRealtime()
        if (::stateStore.isInitialized) stateStore.markLoopTick(System.currentTimeMillis())
    }

    private suspend fun refreshPendingOutcomeCount() {
        try {
            stateStore.recordPendingOutcomeCount(outcomeDao.pendingCount())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Diagnostics must never break fiscal processing.
        }
    }

    private fun normalizedSettings(stored: FiscalFdmSettings): FiscalFdmSettings =
        if (BuildConfig.ENABLE_MOCK_FDM && stored.isMock) {
            stored.copy(
                host = EmbeddedMockFdmContract.HOST,
                port = EmbeddedMockFdmContract.PORT,
                path = EmbeddedMockFdmContract.PATH,
                useTls = false
            )
        } else {
            stored
        }

    private fun publishIdle(message: String, notification: String) {
        stateStore.setServiceStatus(true, false, message)
        updateNotification(notification)
        markLoopProgress()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cookit Fiscal Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service local Cookit chargé du dialogue FDM/Blackbox et de la synchronisation fiscale."
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        notifications.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAgent = PendingIntent.getService(
            this,
            1,
            Intent(this, FiscalAgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fiscal_agent)
            .setContentTitle("Cookit Fiscal Agent")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_fiscal_agent, "Arrêter", stopAgent)
            .build()
    }

    companion object {
        const val ACTION_START = "be.cookit.pos.android.action.FISCAL_AGENT_START"
        const val ACTION_RESUME = "be.cookit.pos.android.action.FISCAL_AGENT_RESUME"
        const val ACTION_STOP = "be.cookit.pos.android.action.FISCAL_AGENT_STOP"

        private const val CHANNEL_ID = "cookit_fiscal_agent"
        private const val NOTIFICATION_ID = 14560
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val IDLE_POLL_MS = 5_000L
        private const val PROCESSED_POLL_MS = 1_000L
        private const val ERROR_RETRY_MS = 5_000L
        private const val RETRY_NOT_READY_MS = 10_000L
        private const val WATCHDOG_CHECK_MS = 15_000L
        private const val WATCHDOG_STALL_MS = 90_000L
        private const val WATCHDOG_RESTART_GRACE_MS = 500L
        private const val MOCK_RESTART_GRACE_MS = 150L
        private const val RETRY_WAIT_TICK_MS = 5_000L
        private const val MANUAL_HOLD_POLL_MS = 15_000L
    }
}
