package be.cookit.pos.android.data.fiscal

import android.content.Context

/**
 * Durable, non-secret runtime state for the Cookit Fiscal Agent.
 *
 * Secrets remain in [FiscalAgentCredentialStore]. This store is deliberately process-independent so
 * the POS UI and the C4 foreground service can exchange small status/counter values through durable
 * SharedPreferences without coupling the fiscal loop to an Activity/ViewModel lifecycle.
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
    val lastPollEpochMs: Long? = null
)

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
        lastPollEpochMs = prefs.getLong(KEY_LAST_POLL, NO_EPOCH).takeIf { it != NO_EPOCH }
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
        editor.apply()
        return load()
    }

    fun recordHeartbeat(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit().putLong(KEY_LAST_HEARTBEAT, epochMs).apply()
        return load()
    }

    fun recordPoll(epochMs: Long): FiscalAgentRuntimeState {
        prefs.edit().putLong(KEY_LAST_POLL, epochMs).apply()
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
        private const val NO_JOB_ID = Long.MIN_VALUE
        private const val NO_EPOCH = Long.MIN_VALUE
    }
}
