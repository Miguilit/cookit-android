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
import be.cookit.pos.android.data.fiscal.FiscalAgentClient
import be.cookit.pos.android.data.fiscal.FiscalAgentCredentialStore
import be.cookit.pos.android.data.fiscal.FiscalAgentException
import be.cookit.pos.android.data.fiscal.FiscalAgentIntegrityException
import be.cookit.pos.android.data.fiscal.FiscalAgentOutcomeDao
import be.cookit.pos.android.data.fiscal.FiscalAgentRunner
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeState
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeStateStore
import be.cookit.pos.android.data.fiscal.FiscalFdmRuntime
import be.cookit.pos.android.data.fiscal.FiscalFdmSettings
import be.cookit.pos.android.data.fiscal.FiscalFdmSettingsStore
import be.cookit.pos.android.data.fiscal.FiscalProviderMappingUnavailable
import be.cookit.pos.android.data.fiscal.FiscalRuntimeRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastLoopProgressElapsedMs: Long = 0L

    private lateinit var stateStore: FiscalAgentRuntimeStateStore
    private lateinit var credentialStore: FiscalAgentCredentialStore
    private lateinit var settingsStore: FiscalFdmSettingsStore
    private lateinit var runtimeRepository: FiscalRuntimeRepository
    private lateinit var client: FiscalAgentClient
    private lateinit var runner: FiscalAgentRunner
    private lateinit var outcomeDao: FiscalAgentOutcomeDao
    private lateinit var fdmRuntime: FiscalFdmRuntime
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
        settingsStore = FiscalFdmSettingsStore(this)
        runtimeRepository = FiscalRuntimeRepository(database.fiscalRuntimeDao())
        client = FiscalAgentClient()
        fdmRuntime = FiscalFdmRuntime(FdmGraphqlClient())
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
                        updateNotification("DEGRADED • opération en cours, aucun retry parallèle")
                        markLoopProgress() // rate-limit the alert while the bounded network call unwinds.
                        continue
                    }

                    stateStore.recordWatchdogRestart(now)
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
                publishIdle("credentials_or_identity_missing", "CONFIG ERROR • identité/credentials manquants")
                delay(RETRY_NOT_READY_MS)
                continue
            }

            if (settings.isMock) {
                val status = embeddedMock.start()
                if (!status.running) {
                    val error = status.lastError.orEmpty().ifBlank { "mock_fdm_unavailable" }
                    stateStore.recordFailure(FiscalAgentRuntimeState.HEALTH_FDM_ERROR, error)
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
                stateStore.recordFailure(FiscalAgentRuntimeState.HEALTH_FDM_ERROR, error)
                publishIdle(
                    "agent_provider_gated:$error",
                    "FDM ERROR • adapter verrouillé"
                )
                delay(RETRY_NOT_READY_MS)
                continue
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
                    updateNotification("OFFLINE • heartbeat Cloud en échec")
                    markLoopProgress()
                }
            }

            stateStore.setServiceStatus(true, true, "auto_polling")
            stateStore.recordPollAttempt(System.currentTimeMillis())
            updateNotification("${stateStore.load().health} • recherche d’un job")
            markLoopProgress()

            val result = try {
                runner.processNext(credentials, identity, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val health = classifyFailure(error)
                stateStore.recordFailure(health, "job:${error.message.orEmpty()}")
                stateStore.setServiceStatus(
                    running = true,
                    busy = false,
                    message = "job_failed:${error.message.orEmpty().take(220)}"
                )
                refreshPendingOutcomeCount()
                updateNotification("$health • retry fiscal automatique")
                markLoopProgress()
                delay(ERROR_RETRY_MS)
                continue
            }

            val completedAt = System.currentTimeMillis()
            stateStore.recordPoll(completedAt)
            refreshPendingOutcomeCount()
            markLoopProgress()

            if (!result.processed) {
                stateStore.recordHealthy(completedAt)
                stateStore.setServiceStatus(true, false, "job_idle")
                updateNotification("CONNECTED • aucun job en attente")
                delay(IDLE_POLL_MS)
                continue
            }

            val persisted = stateStore.recordProcessed(
                jobId = result.jobId,
                receiptNumber = result.receiptNumber
            )
            stateStore.recordHealthy(completedAt)
            stateStore.setServiceStatus(
                running = true,
                busy = false,
                message = "job_ok:${result.jobId}:${result.receiptNumber.orEmpty()}:${result.duplicate}:${result.replayedFromLocalJournal}"
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
        is FiscalAgentException -> FiscalAgentRuntimeState.HEALTH_OFFLINE
        is FiscalAgentIntegrityException -> FiscalAgentRuntimeState.HEALTH_DEGRADED
        is IOException -> FiscalAgentRuntimeState.HEALTH_DEGRADED
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
    }
}
