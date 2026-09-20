package be.cookit.pos.android.data.fiscal

import android.content.Context

/**
 * Durable, non-secret runtime state for the Cookit Fiscal Agent.
 *
 * Secrets remain in [FiscalAgentCredentialStore]. This store only persists the operator/device
 * preference to keep automatic polling enabled plus small UI counters that are safe to restore
 * after Android process death or an APK upgrade.
 */
data class FiscalAgentRuntimeState(
    val autoEnabled: Boolean = false,
    val processedJobs: Int = 0,
    val lastJobId: Long? = null,
    val lastReceipt: String? = null
)

class FiscalAgentRuntimeStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): FiscalAgentRuntimeState = FiscalAgentRuntimeState(
        autoEnabled = prefs.getBoolean(KEY_AUTO_ENABLED, false),
        processedJobs = prefs.getInt(KEY_PROCESSED_JOBS, 0).coerceAtLeast(0),
        lastJobId = prefs.getLong(KEY_LAST_JOB_ID, NO_JOB_ID).takeIf { it != NO_JOB_ID },
        lastReceipt = prefs.getString(KEY_LAST_RECEIPT, null)?.takeIf { it.isNotBlank() }
    )

    fun setAutoEnabled(enabled: Boolean): FiscalAgentRuntimeState {
        prefs.edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply()
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
        private const val KEY_PROCESSED_JOBS = "processed_jobs"
        private const val KEY_LAST_JOB_ID = "last_job_id"
        private const val KEY_LAST_RECEIPT = "last_receipt"
        private const val NO_JOB_ID = Long.MIN_VALUE
    }
}
