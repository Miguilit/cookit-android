package be.cookit.pos.android.data.fiscal

class FiscalAgentDiagnosticLogger(
    private val dao: FiscalAgentDiagnosticDao,
    private val stateStore: FiscalAgentRuntimeStateStore
) {
    suspend fun record(
        eventType: String,
        health: String = stateStore.load().health,
        jobId: Long? = null,
        jobPhase: String? = null,
        provider: String? = null,
        runtimeId: String? = null,
        message: String? = null,
        errorClass: String? = null,
        retryCount: Int? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val state = stateStore.load()
        val cleanMessage = message?.let { sanitize(it) }?.take(320)
        val latest = dao.latestOne()
        val duplicateRecent = latest != null &&
            latest.eventType == eventType &&
            latest.health == health &&
            latest.jobId == jobId &&
            latest.jobPhase == jobPhase &&
            latest.message == cleanMessage &&
            now - latest.createdAtEpochMs < DEDUPE_WINDOW_MS

        if (duplicateRecent) return

        dao.insert(
            FiscalAgentDiagnosticEntity(
                createdAtEpochMs = now,
                health = health,
                eventType = eventType,
                jobId = jobId,
                jobPhase = jobPhase,
                provider = provider,
                runtimeId = runtimeId,
                connectivity = if (health == FiscalAgentRuntimeState.HEALTH_OFFLINE) CONNECTIVITY_OFFLINE else CONNECTIVITY_ONLINE,
                message = cleanMessage,
                errorClass = errorClass?.take(160),
                retryCount = retryCount ?: state.retryAttempt.takeIf { it > 0 } ?: state.consecutiveFailures,
                pendingOutcomes = state.pendingOutcomeCount,
                watchdogCount = state.watchdogRestarts
            )
        )
        dao.pruneToLatest(MAX_EVENTS)
    }

    companion object {
        const val EVENT_SERVICE_STARTED = "SERVICE_STARTED"
        const val EVENT_SERVICE_STOPPED = "SERVICE_STOPPED"
        const val EVENT_HEALTH_FAILURE = "HEALTH_FAILURE"
        const val EVENT_RECOVERED = "RECOVERED"
        const val EVENT_WATCHDOG = "WATCHDOG"
        const val EVENT_JOB_PHASE = "JOB_PHASE"
        const val EVENT_JOB_COMPLETED = "JOB_COMPLETED"
        const val EVENT_RETRY_DECISION = "RETRY_DECISION"
        const val EVENT_RETRY_SCHEDULED = "RETRY_SCHEDULED"
        const val EVENT_RETRY_EXHAUSTED = "RETRY_EXHAUSTED"
        const val EVENT_JOB_TERMINAL_FAILED = "JOB_TERMINAL_FAILED"
        const val EVENT_MANUAL_HOLD = "MANUAL_HOLD"
        const val EVENT_MANUAL_RESUME = "MANUAL_RESUME"

        const val CONNECTIVITY_ONLINE = "ONLINE"
        const val CONNECTIVITY_OFFLINE = "OFFLINE"

        private fun sanitize(value: String): String = value
            .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(device[_ -]?token|access[_ -]?token|token)\\s*[:=]\\s*[A-Za-z0-9._~+/-]{12,}"), "$1=[REDACTED]")

        private const val DEDUPE_WINDOW_MS = 30_000L
        private const val MAX_EVENTS = 250
    }
}
