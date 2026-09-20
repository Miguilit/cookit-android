package be.cookit.pos.android.data.fiscal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable local journal for a provider result obtained for a cloud fiscal job.
 *
 * The critical invariant is: once the local FDM returns a valid receipt, that result is persisted
 * before Cookit Android attempts /submitted or /acknowledge against Cookit Cloud. If the network
 * disappears afterwards, the next lease recovery replays the stored cloud acknowledgement instead
 * of submitting the fiscal event to the FDM again.
 */
@Entity(
    tableName = "fiscal_agent_outcomes",
    indices = [
        Index(value = ["public_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["state", "updated_at_epoch_ms"])
    ]
)
data class FiscalAgentOutcomeEntity(
    @PrimaryKey
    @ColumnInfo(name = "transaction_id")
    val transactionId: Long,
    @ColumnInfo(name = "public_id")
    val publicId: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "snapshot_hash")
    val snapshotHash: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "receipt_number")
    val receiptNumber: String,
    @ColumnInfo(name = "signature")
    val signature: String?,
    @ColumnInfo(name = "verification_code")
    val verificationCode: String?,
    @ColumnInfo(name = "provider_reference")
    val providerReference: String?,
    @ColumnInfo(name = "raw_response_json")
    val rawResponseJson: String,
    @ColumnInfo(name = "provider_duplicate", defaultValue = "0")
    val providerDuplicate: Boolean = false,
    @ColumnInfo(name = "state")
    val state: String = STATE_PROVIDER_ACCEPTED,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
) {
    companion object {
        const val STATE_PROVIDER_ACCEPTED = "provider_accepted"
        const val STATE_CLOUD_SUBMITTED = "cloud_submitted"
        const val STATE_CLOUD_ACKED = "cloud_acked"
    }
}
