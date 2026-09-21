package be.cookit.pos.android.data.fiscal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fiscal_agent_diagnostics",
    indices = [
        Index(value = ["created_at_epoch_ms"]),
        Index(value = ["health", "created_at_epoch_ms"]),
        Index(value = ["event_type", "created_at_epoch_ms"]),
        Index(value = ["job_id", "created_at_epoch_ms"])
    ]
)
data class FiscalAgentDiagnosticEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "health")
    val health: String,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "job_id")
    val jobId: Long? = null,
    @ColumnInfo(name = "job_phase")
    val jobPhase: String? = null,
    @ColumnInfo(name = "provider")
    val provider: String? = null,
    @ColumnInfo(name = "runtime_id")
    val runtimeId: String? = null,
    @ColumnInfo(name = "connectivity")
    val connectivity: String,
    @ColumnInfo(name = "message")
    val message: String? = null,
    @ColumnInfo(name = "error_class")
    val errorClass: String? = null,
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo(name = "pending_outcomes", defaultValue = "0")
    val pendingOutcomes: Int = 0,
    @ColumnInfo(name = "watchdog_count", defaultValue = "0")
    val watchdogCount: Int = 0
)
