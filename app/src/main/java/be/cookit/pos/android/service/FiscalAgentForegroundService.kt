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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.MainActivity
import be.cookit.pos.android.R
import be.cookit.pos.android.data.fiscal.CookitLocalDatabase
import be.cookit.pos.android.data.fiscal.EmbeddedMockFdmContract
import be.cookit.pos.android.data.fiscal.EmbeddedMockFdmServer
import be.cookit.pos.android.data.fiscal.FdmGraphqlClient
import be.cookit.pos.android.data.fiscal.FiscalAgentClient
import be.cookit.pos.android.data.fiscal.FiscalAgentCredentialStore
import be.cookit.pos.android.data.fiscal.FiscalAgentRunner
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeStateStore
import be.cookit.pos.android.data.fiscal.FiscalFdmRuntime
import be.cookit.pos.android.data.fiscal.FiscalFdmSettings
import be.cookit.pos.android.data.fiscal.FiscalFdmSettingsStore
import be.cookit.pos.android.data.fiscal.FiscalRuntimeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * C4 device-level Fiscal Agent runtime.
 *
 * The automatic cloud polling loop no longer belongs to the POS ViewModel. Once an authorized
 * operator enables Auto, this foreground service owns heartbeat -> jobs/next -> local FDM ->
 * submitted/acknowledge and keeps the durable Room outcome journal alive while the UI is closed.
 *
 * The real Checkbox/Eutronix adapter remains fail-closed until the certified mapping is installed.
 */
class FiscalAgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private lateinit var stateStore: FiscalAgentRuntimeStateStore
    private lateinit var credentialStore: FiscalAgentCredentialStore
    private lateinit var settingsStore: FiscalFdmSettingsStore
    private lateinit var runtimeRepository: FiscalRuntimeRepository
    private lateinit var client: FiscalAgentClient
    private lateinit var runner: FiscalAgentRunner
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
        runner = FiscalAgentRunner(client, fdmRuntime, database.fiscalAgentOutcomeDao())
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
        startForegroundCompat(buildNotification("Démarrage du Fiscal Agent…"))
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        releaseScreenOffWakeLock()
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
        loopJob = scope.launch { runLoop() }
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
    }

    private suspend fun runLoop() {
        stateStore.setServiceStatus(true, false, "service_running")
        updateNotification("Fiscal Agent actif • initialisation")

        var heartbeatAt = 0L
        while (currentCoroutineContext().isActive && stateStore.load().autoEnabled) {
            val credentials = credentialStore.load()
            val identity = runtimeRepository.currentIdentity()
            val settings = normalizedSettings(settingsStore.load())

            if (credentials == null || identity == null || identity.restaurantId == null || identity.branchId == null) {
                publishIdle("credentials_or_identity_missing", "En attente de l’identité/credentials")
                delay(RETRY_NOT_READY_MS)
                continue
            }

            if (settings.isMock) {
                val status = embeddedMock.start()
                if (!status.running) {
                    publishIdle(
                        "agent_mock_unavailable:${status.lastError.orEmpty()}",
                        "Mock FDM indisponible"
                    )
                    delay(RETRY_NOT_READY_MS)
                    continue
                }
            }

            val readiness = fdmRuntime.readiness(settings)
            if (!readiness.readyForFiscalization) {
                publishIdle(
                    "agent_provider_gated:${readiness.reason.orEmpty()}",
                    "Adapter FDM verrouillé"
                )
                delay(RETRY_NOT_READY_MS)
                continue
            }

            val now = System.currentTimeMillis()
            if (now - heartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                try {
                    client.heartbeat(
                        credentials,
                        client.defaultHeartbeatPayload(identity, settings)
                    )
                    heartbeatAt = now
                    stateStore.recordHeartbeat(now)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    stateStore.setServiceStatus(
                        running = true,
                        busy = false,
                        message = "heartbeat_failed:${error.message.orEmpty().take(180)}"
                    )
                    updateNotification("Cloud indisponible • nouvelle tentative automatique")
                }
            }

            stateStore.setServiceStatus(true, true, "auto_polling")
            updateNotification("Fiscal Agent actif • recherche d’un job")

            val result = try {
                runner.processNext(credentials, identity, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                stateStore.setServiceStatus(
                    running = true,
                    busy = false,
                    message = "job_failed:${error.message.orEmpty().take(220)}"
                )
                updateNotification("Retry fiscal automatique")
                delay(ERROR_RETRY_MS)
                continue
            }

            stateStore.recordPoll(System.currentTimeMillis())
            if (!result.processed) {
                stateStore.setServiceStatus(true, false, "job_idle")
                updateNotification("Fiscal Agent actif • aucun job en attente")
                delay(IDLE_POLL_MS)
                continue
            }

            val persisted = stateStore.recordProcessed(
                jobId = result.jobId,
                receiptNumber = result.receiptNumber
            )
            stateStore.setServiceStatus(
                running = true,
                busy = false,
                message = "job_ok:${result.jobId}:${result.receiptNumber.orEmpty()}:${result.duplicate}:${result.replayedFromLocalJournal}"
            )
            updateNotification(
                buildString {
                    append("Job #${result.jobId ?: "?"} traité")
                    persisted.lastReceipt?.let { append(" • $it") }
                }
            )
            delay(PROCESSED_POLL_MS)
        }

        stateStore.setServiceStatus(false, false, "auto_stopped")
        stopSelf()
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
    }
}
