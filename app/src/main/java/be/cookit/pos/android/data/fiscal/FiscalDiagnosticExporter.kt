package be.cookit.pos.android.data.fiscal

import android.content.Context
import be.cookit.pos.android.BuildConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class FiscalDiagnosticExporter(
    private val context: Context,
    private val diagnosticDao: FiscalAgentDiagnosticDao,
    private val runtimeRepository: FiscalRuntimeRepository,
    private val stateStore: FiscalAgentRuntimeStateStore,
    private val settingsStore: FiscalFdmSettingsStore
) {
    suspend fun export(): File {
        val identity = runtimeRepository.currentIdentity()
        val state = stateStore.load()
        val settings = settingsStore.load()
        val events = diagnosticDao.latest(250)

        val root = JSONObject()
            .put("schema", "cookit.fiscal.diagnostic.v1")
            .put("generated_at_epoch_ms", System.currentTimeMillis())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("provider", settings.provider)
            .put("runtime_id", identity?.runtimeId)
            .put("terminal_id", identity?.terminalId)
            .put("restaurant_id", identity?.restaurantId)
            .put("branch_id", identity?.branchId)
            .put("health", state.health)
            .put("service_running", state.serviceRunning)
            .put("auto_enabled", state.autoEnabled)
            .put("processed_jobs", state.processedJobs)
            .put("pending_outcomes", state.pendingOutcomeCount)
            .put("watchdog_restarts", state.watchdogRestarts)
            .put("last_heartbeat_epoch_ms", state.lastHeartbeatEpochMs)
            .put("last_poll_epoch_ms", state.lastPollEpochMs)
            .put("last_success_epoch_ms", state.lastSuccessEpochMs)
            .put("last_error_epoch_ms", state.lastErrorEpochMs)
            .put("last_error", state.lastError?.let(::redact))
            .put("active_job_id", state.activeJobId)
            .put("active_job_phase", state.activeJobPhase)
            .put("retry_disposition", state.retryDisposition)
            .put("retry_at_epoch_ms", state.retryAtEpochMs)
            .put("retry_attempt", state.retryAttempt)
            .put("terminal_failures", state.terminalFailures)
            .put("last_terminal_job_id", state.lastTerminalJobId)
            .put("manual_hold", state.manualHold)

        val eventArray = JSONArray()
        events.forEach { event ->
            eventArray.put(
                JSONObject()
                    .put("timestamp_epoch_ms", event.createdAtEpochMs)
                    .put("health", event.health)
                    .put("event_type", event.eventType)
                    .put("job_id", event.jobId)
                    .put("job_phase", event.jobPhase)
                    .put("provider", event.provider)
                    .put("runtime_id", event.runtimeId)
                    .put("connectivity", event.connectivity)
                    .put("message", event.message?.let(::redact))
                    .put("error_class", event.errorClass)
                    .put("retry_count", event.retryCount)
                    .put("pending_outcomes", event.pendingOutcomes)
                    .put("watchdog_count", event.watchdogCount)
            )
        }
        root.put("events", eventArray)

        val dir = File(context.cacheDir, "fiscal-diagnostics").apply { mkdirs() }
        return File(dir, "cookit-fiscal-diagnostic-${System.currentTimeMillis()}.json").apply {
            writeText(root.toString(2), Charsets.UTF_8)
        }
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(device[_ -]?token|access[_ -]?token|token)\\s*[:=]\\s*[A-Za-z0-9._~+/-]{12,}"), "$1=[REDACTED]")
}
