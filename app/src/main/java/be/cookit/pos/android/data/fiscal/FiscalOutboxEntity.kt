package be.cookit.pos.android.data.fiscal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fiscal_outbox",
    indices = [
        Index(value = ["local_event_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["order_id"]),
        Index(value = ["status", "next_attempt_at_epoch_ms"])
    ]
)
data class FiscalOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_db_id")
    val localDbId: Long = 0,
    @ColumnInfo(name = "local_event_id")
    val localEventId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "runtime_id")
    val runtimeId: String,
    @ColumnInfo(name = "terminal_id")
    val terminalId: String,
    @ColumnInfo(name = "restaurant_id")
    val restaurantId: Long?,
    @ColumnInfo(name = "branch_id")
    val branchId: Long?,
    @ColumnInfo(name = "order_id")
    val orderId: Long,
    @ColumnInfo(name = "order_type")
    val orderType: String,
    @ColumnInfo(name = "payment_method")
    val paymentMethod: String,
    @ColumnInfo(name = "sce_event_class")
    val sceEventClass: String = "N",
    @ColumnInfo(name = "sce_event_type")
    val sceEventType: String = "sale",
    @ColumnInfo(name = "currency")
    val currency: String = "EUR",
    @ColumnInfo(name = "gross_total_minor")
    val grossTotalMinor: Long,
    @ColumnInfo(name = "snapshot_json")
    val snapshotJson: String,
    @ColumnInfo(name = "snapshot_hash")
    val snapshotHash: String,
    @ColumnInfo(name = "status")
    val status: String = STATUS_PREPARED,
    @ColumnInfo(name = "cloud_transaction_id")
    val cloudTransactionId: String? = null,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at_epoch_ms")
    val nextAttemptAtEpochMs: Long = 0,
    @ColumnInfo(name = "last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "activated_at_epoch_ms")
    val activatedAtEpochMs: Long? = null,
    @ColumnInfo(name = "cloud_synced_at_epoch_ms")
    val cloudSyncedAtEpochMs: Long? = null,
    @ColumnInfo(name = "fdm_submitted_at_epoch_ms")
    val fdmSubmittedAtEpochMs: Long? = null,
    @ColumnInfo(name = "fiscalized_at_epoch_ms")
    val fiscalizedAtEpochMs: Long? = null
) {
    companion object {
        const val STATUS_PREPARED = "prepared"
        const val STATUS_PENDING = "pending"
        const val STATUS_CLOUD_QUEUED = "cloud_queued"
        const val STATUS_FDM_SUBMITTED = "fdm_submitted"
        const val STATUS_FISCALIZED = "fiscalized"
        const val STATUS_RETRY = "retry"
        const val STATUS_BLOCKED_PROFILE_OFF = "blocked_profile_off"
        const val STATUS_FAILED = "failed"
    }
}
