package be.cookit.pos.android.data.fiscal

import android.content.Context

/**
 * Durable, non-secret runtime/health state for the Cookit Fiscal Agent.
 *
 * Secrets remain in [FiscalAgentCredentialStore]. C5.1 extends the original C4 state with a small
 * health/watchdog ledger so the foreground service and POS UI can diagnose stalls and degraded
 * connectivity without coupling the fiscal loop to an Activity/ViewModel lifecycle.
 */
data class FiscalAgentRuntimeState(
    val autoEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val serviceBusy: Boolean = false,
    val processedJobs: Int = 0,
    val lastJobId: Long? = null,
    val lastReceipt: String? = null,
    val lastMessage: String? = null,
    val lastHeartbeatEpochMs: Long? = null,
    val lastPollEpochMs: Long? = null,
    val health: String = HEALTH_STOPPED,
    val serviceStartedEpochMs: Long? = null,
    val lastLoopTickEpochMs: Long? = null,
    val lastPollAttemptEpochMs: Long? = null,
    val lastSuccessEpochMs: Long? = null,
    val lastErrorEpochMs: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val watchdogRestarts: Int = 0,
    val wakeLockHeld: Boolean = false,
    val pendingOutcomeCount: Int = 0,
    val activeJobId: Long? = null,
    val activeJobPhase: String? = null
) {
    companion object {
        const val HEALTH_STOPPED = "STOPPED"
        const val HEALTH_STARTING = "STARTING"
        const val HEALTH_CONNECTED = "CONNECTED"
        const val HEALTH_DEGRADED = "DEGRADED"
        const val HEALTH_OFFLINE = "OFFLINE"
        const val HEALTH_FDM_ERROR = "FDM_ERROR"
        const val HEALTH_CONFIG_ERROR = "CONFIG_ERROR"
    }
}

class FiscalAgentRuntimeStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): FiscalAgentRuntimeState = FiscalAgentRuntimeState(
        autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
        serviceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false),
        serviceBusy = prefs.getBoolean(KEY_SERVICE_BUSY, false),
        processedJobs = prefs.getInt(KEY_PROCESSED_JOBS, 0).coerceAtLeast(0),
        lastJobId = prefs.getLong(KEY_LAST_JOB_ID, NO_JOB_ID).takeIf { it != NO_JOB_ID },
        lastReceipt = prefs.getString(KEY_LAST_RECEIPT, null)?.takeIf { it.isNotBlank() },
        lastMessage = prefs.getString(KEY_LAST_MESSAGE, null)?.takeIf { it.isNotBlank() },
        lastHeartbeatEpochMs = prefs.getLong(KEY_LAST_HEARTBEAT, NO_EPOCH).takeIf { it != NO_EPOCH },
        lastPollEpochMs = prefs.getLong(KEY_LAST_POLL, NO_EPOCH).takeIf { it != NO_EPOCH },
        health = prefs.getString(KEY_HEALTH, FiscalAgentRuntimeState.HEALTH_STOPPED)
            ?.takeIf { it.isNotBlank() }
            ?: FiscalAgentRuntimeState.HEALTH_STOPPED,
        serviceStartedEpochMs = prefs.getLong(KEY_SERVICE_STARTED, NO_EPOCH).takeIf { it != NO_EPOCH },
        lastLoopTickEpochMs = prefs.getLong(KEY_LAST_LOOP_TICK, NO_EPOCH).takeIf { it != NO_EPOCH },
        lastPollAttemptEpochMs = prefs.getLong(KEY_LAST_POLL_ATTEMPT, NO_EPOCH).takeIf { it != NO_EPOCH },
        lastSuccessEpochMs = prefs.getLong(KEY_LAST_SUCCESS, NO_EPOCH).takeIf { it != NO_EPOCH },
        lastErrorEpochMs = prefs.getLong(KEY_LAST_ERROR_AT, NO_EPOCH).takeIf { it != NO_EPOCH },
        lastError = prefs.getString(KEY_LAST_ERROR, null)?.takeIf { it.isNotBlank() },
        consecutiveFailures = prefs.getInt(KEY_CONSECUTIVE_FAILURES, 0).coerceAtLeast(0),
        watchdogRestarts = prefs.getInt(KEY_WATCHDOG_RESTARTS, 0).coerceAtLeast(0),
        wakeLockHeld = prefs.getBoolean(KEY_WAKE_LOCK_HELD, false),
        pendingOutcomeCount = prefs.getInt(KEY_PENDING_OUTCOMES, 0).coerceAtLeast(0),
        activeJobId = prefs.getLong(KEY_ACTIVE_JOB_ID, NO_JOB_ID).takeIf { it != NO_JOB_ID },
        activeJobPhase = prefs.getString(KEY_ACTIVE_JOB_PHASE, null)?.takeIf { it.isNotBlank() }
    )

    fun setAutoEnabled(enabled: Boolean): FiscalAgentRuntimeState {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
        return load()
    }

    fun setServiceStatus(
        running: Boolean,
        busy: Boolean,
        message: String? = null
    ): FiscalAgentRuntimeState {
        val editor = prefs.edit()
            .putBoolean(KEY_SERVICE_RUNNING, running)
            .putBoolean(KEY_SERVICE_BUSY, busy)
        if (message.isNullOrBlank()) editor.remove(KEY_LAST_MESSAGE) else editor.putString(KEY_LAST_MESSAGE, message)
        if (!running && !load().autoEnabled) editor.putString(KEY_HEALTH, FiscalAgentRuntimeState.HEALTH_STOPPED)
        editor.apply()
        return load()
    }

    fun markServiceStarted(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit()
            .putBoolean(KEY_SERVICE_RUNNING, true)
            .putBoolean(KEY_SERVICE_BUSY, false)
            .putString(KEY_HEALTH, FiscalAgentRuntimeState.HEALTH_STARTING)
            .putLong(KEY_SERVICE_STARTED, epochMs)
            .putLong(KEY_LAST_LOOP_TICK, epochMs)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .apply()
        return load()
    }

    fun markLoopTick(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit().putLong(KEY_LAST_LOOP_TICK, epochMs).apply()
        return load()
    }

    fun recordHeartbeat(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit().putLong(KEY_LAST_HEARTBEAT, epochMs).apply()
        return load()
    }

    fun recordPollAttempt(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit().putLong(KEY_LAST_POLL_ATTEMPT, epochMs).apply()
        return load()
    }

    fun recordPoll(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit().putLong(KEY_LAST_POLL, epochMs).apply()
        return load()
    }

    fun recordHealthy(epochMs: Long, message: String? = null, clearError: Boolean = false): FiscalAgentRuntimeState {
        val current = load()
        val unresolvedJobFailure = current.activeJobId != null && current.activeJobPhase == PHASE_ERROR
        val editor = prefs.edit().putLong(KEY_LAST_SUCCESS, epochMs)

        if (!unresolvedJobFailure) {
            editor.putString(KEY_HEALTH, FiscalAgentRuntimeState.HEALTH_CONNECTED)
        }
        if (clearError) {
            editor.putInt(KEY_CONSECUTIVE_FAILURES, 0)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_LAST_ERROR_AT)
        }
        if (!message.isNullOrBlank()) editor.putString(KEY_LAST_MESSAGE, message)
        editor.apply()
        return load()
    }

    fun setActiveJob(jobId: Long, phase: String): FiscalAgentRuntimeState {
        prefs.edit()
            .putLong(KEY_ACTIVE_JOB_ID, jobId)
            .putString(KEY_ACTIVE_JOB_PHASE, phase)
            .apply()
        return load()
    }

    fun markActiveJobError(health: String, error: String, epochMs: Long = System.currentTimeMillis()): FiscalAgentRuntimeState {
        val current = load()
        val editor = prefs.edit()
            .putString(KEY_ACTIVE_JOB_PHASE, PHASE_ERROR)
            .putString(KEY_HEALTH, health)
            .putLong(KEY_LAST_ERROR_AT, epochMs)
            .putString(KEY_LAST_ERROR, error.take(320))
            .putInt(KEY_CONSECUTIVE_FAILURES, current.consecutiveFailures + 1)
        editor.apply()
        return load()
    }

    fun clearActiveJobAfterSuccess(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit()
            .remove(KEY_ACTIVE_JOB_ID)
            .remove(KEY_ACTIVE_JOB_PHASE)
            .putString(KEY_HEALTH, FiscalAgentRuntimeState.HEALTH_CONNECTED)
            .putLong(KEY_LAST_SUCCESS, epochMs)
            .putInt(KEY_CONSECUTIVE_FAILURES, 0)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_LAST_ERROR_AT)
            .apply()
        return load()
    }

    fun recordFailure(
        health: String,
        error: String,
        epochMs: Long = System.currentTimeMillis()
    ): FiscalAgentRuntimeState {
        val current = load()
        prefs.edit()
            .putString(KEY_HEALTH, health)
            .putLong(KEY_LAST_ERROR_AT, epochMs)
            .putString(KEY_LAST_ERROR, error.take(320))
            .putInt(KEY_CONSECUTIVE_FAILURES, current.consecutiveFailures + 1)
            .apply()
        return load()
    }

    fun recordWatchdogRestart(epochMs: Long): FiscalAgentRuntimeState {
        val current = load()
        prefs.edit()
            .putInt(KEY_WATCHDOG_RESTARTS, current.watchdogRestarts + 1)
            .putString(KEY_HEALTH, FiscalAgentRuntimeState.HEALTH_DEGRADED)
            .putLong(KEY_LAST_ERROR_AT, epochMs)
            .putString(KEY_LAST_ERROR, "watchdog_loop_stalled")
            .putInt(KEY_CONSECUTIVE_FAILURES, current.consecutiveFailures + 1)
            .putString(KEY_LAST_MESSAGE, "watchdog_restart")
            .apply()
        return load()
    }

    fun setWakeLockHeld(held: Boolean): FiscalAgentRuntimeState {
        prefs.edit().putBoolean(KEY_WAKE_LOCK_HELD, held).apply()
        return load()
    }

    fun recordPendingOutcomeCount(count: Int): FiscalAgentRuntimeState {
        prefs.edit().putInt(KEY_PENDING_OUTCOMES, count.coerceAtLeast(0)).apply()
        return load()
    }

    fun recordProcessed(jobId: Long?, receiptNumber: String?): FiscalAgentRuntimeState {
        val current = load()
        val edit = prefs.edit()
            .putInt(KEY_PROCESSED_JOBS, current.processedJobs + 1)

        if (jobId != null) edit.putLong(KEY_LAST_JOB_ID, jobId)
        if (!receiptNumber.isNullOrBlank()) edit.putString(KEY_LAST_RECEIPT, receiptNumber)
        edit.apply()
        return load()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "cookit_fiscal_agent_runtime_state_v1"
        private const val KEY_AUTO_ENABLED = "auto_enabled"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val KEY_SERVICE_BUSY = "service_busy"
        private const val KEY_PROCESSED_JOBS = "processed_jobs"
        private const val KEY_LAST_JOB_ID = "last_job_id"
        private const val KEY_LAST_RECEIPT = "last_receipt"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat_epoch_ms"
        private const val KEY_LAST_POLL = "last_poll_epoch_ms"
        private const val KEY_HEALTH = "health"
        private const val KEY_SERVICE_STARTED = "service_started_epoch_ms"
        private const val KEY_LAST_LOOP_TICK = "last_loop_tick_epoch_ms"
        private const val KEY_LAST_POLL_ATTEMPT = "last_poll_attempt_epoch_ms"
        private const val KEY_LAST_SUCCESS = "last_success_epoch_ms"
        private const val KEY_LAST_ERROR_AT = "last_error_epoch_ms"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
        private const val KEY_WATCHDOG_RESTARTS = "watchdog_restarts"
        private const val KEY_WAKE_LOCK_HELD = "wake_lock_held"
        private const val KEY_PENDING_OUTCOMES = "pending_outcome_count"
        private const val KEY_ACTIVE_JOB_ID = "active_job_id"
        private const val KEY_ACTIVE_JOB_PHASE = "active_job_phase"

        const val PHASE_CLAIMED = "CLAIMED"
        const val PHASE_FDM_CALL = "FDM_CALL"
        const val PHASE_PROVIDER_ACCEPTED = "PROVIDER_ACCEPTED"
        const val PHASE_REPLAY_LOCAL = "REPLAY_LOCAL"
        const val PHASE_CLOUD_SUBMIT = "CLOUD_SUBMIT"
        const val PHASE_CLOUD_ACK = "CLOUD_ACK"
        const val PHASE_ERROR = "ERROR"

        private const val NO_JOB_ID = Long.MIN_VALUE
        private const val NO_EPOCH = Long.MIN_VALUE
    }
}
