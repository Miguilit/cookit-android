package be.cookit.pos.android.data.fiscal

import android.content.Context
import org.json.JSONObject

data class Module2TrainingReceiptRecord(
    val localEventId: String,
    val snapshotHash: String,
    val orderId: Long,
    val posFiscalTicketNo: Int?,
    val fdmId: String?,
    val eventCounter: Int?,
    val totalCounter: Int?,
    val sentAtEpochMs: Long
)

/**
 * A local duplicate guard for the manual A15.0E Module2 TRAINING flow.
 *
 * It is intentionally scoped to simulator/training submissions. Production idempotency must be
 * reconciled against the certified provider/FDM outcome and must not rely on SharedPreferences.
 */
class Module2TrainingReceiptStore(context: Context) {
    private val preferences = context.getSharedPreferences("cookit_module2_training_receipts", Context.MODE_PRIVATE)

    fun load(localEventId: String): Module2TrainingReceiptRecord? {
        val raw = preferences.getString(key(localEventId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Module2TrainingReceiptRecord(
                localEventId = json.optString("local_event_id"),
                snapshotHash = json.optString("snapshot_hash"),
                orderId = json.optLong("order_id"),
                posFiscalTicketNo = json.optInt("pos_fiscal_ticket_no").takeIf { json.has("pos_fiscal_ticket_no") },
                fdmId = json.optString("fdm_id").takeIf { it.isNotBlank() },
                eventCounter = json.optInt("event_counter").takeIf { json.has("event_counter") },
                totalCounter = json.optInt("total_counter").takeIf { json.has("total_counter") },
                sentAtEpochMs = json.optLong("sent_at_epoch_ms")
            )
        }.getOrNull()
    }

    fun isSent(localEventId: String, snapshotHash: String): Boolean =
        load(localEventId)?.snapshotHash?.equals(snapshotHash, ignoreCase = true) == true

    fun markSent(entity: FiscalOutboxEntity, result: Module2TrainingSaleResult, sentAtEpochMs: Long = System.currentTimeMillis()) {
        require(result.success) { "Only accepted Module2 TRAINING outcomes can be persisted" }
        val json = JSONObject()
            .put("local_event_id", entity.localEventId)
            .put("snapshot_hash", entity.snapshotHash)
            .put("order_id", entity.orderId)
            .put("sent_at_epoch_ms", sentAtEpochMs)
        result.posFiscalTicketNo?.let { json.put("pos_fiscal_ticket_no", it) }
        result.fdmId?.let { json.put("fdm_id", it) }
        result.eventCounter?.let { json.put("event_counter", it) }
        result.totalCounter?.let { json.put("total_counter", it) }
        preferences.edit().putString(key(entity.localEventId), json.toString()).apply()
    }

    private fun key(localEventId: String) = "event_${localEventId.take(128)}"
}
