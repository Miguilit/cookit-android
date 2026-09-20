package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

class FiscalAgentIntegrityException(message: String) : IllegalStateException(message)

data class FiscalAgentJob(
    val id: Long,
    val publicId: String,
    val restaurantId: Long,
    val branchId: Long,
    val localEventId: String?,
    val idempotencyKey: String?,
    val orderId: Long?,
    val type: String,
    val documentKind: String,
    val sceEventClass: String?,
    val sceEventType: String?,
    val currency: String,
    val grossTotal: String,
    val snapshot: JSONObject,
    val snapshotHash: String,
    val attempts: Int,
    val metadata: JSONObject?
) {
    fun validateScope(identity: FiscalRuntimeIdentity) {
        val expectedRestaurant = identity.restaurantId
            ?: throw FiscalAgentIntegrityException("Runtime restaurant binding is missing")
        val expectedBranch = identity.branchId
            ?: throw FiscalAgentIntegrityException("Runtime branch binding is missing")

        if (restaurantId != expectedRestaurant || branchId != expectedBranch) {
            throw FiscalAgentIntegrityException(
                "Cloud fiscal job scope mismatch: job=$restaurantId/$branchId runtime=$expectedRestaurant/$expectedBranch"
            )
        }
    }

    fun asProviderEvent(identity: FiscalRuntimeIdentity): FiscalOutboxEntity {
        validateScope(identity)
        val canonicalSnapshot = FiscalCanonicalJson.encode(snapshot.toCanonicalValue())
        val calculatedHash = FiscalCanonicalJson.sha256Hex(canonicalSnapshot)
        if (!calculatedHash.equals(snapshotHash, ignoreCase = true)) {
            throw FiscalAgentIntegrityException(
                "Cloud fiscal snapshot SHA-256 mismatch for transaction $id"
            )
        }

        val now = System.currentTimeMillis()
        return FiscalOutboxEntity(
            localEventId = localEventId?.takeIf { it.isNotBlank() } ?: "cloud:$publicId",
            idempotencyKey = idempotencyKey?.takeIf { it.isNotBlank() } ?: "cloud:$publicId",
            runtimeId = identity.runtimeId,
            terminalId = identity.terminalId,
            restaurantId = restaurantId,
            branchId = branchId,
            orderId = orderId ?: 0L,
            orderType = "cloud_fiscal_job",
            paymentMethod = "cloud_fiscal_job",
            sceEventClass = sceEventClass?.takeIf { it.isNotBlank() } ?: "N",
            sceEventType = sceEventType?.takeIf { it.isNotBlank() } ?: type,
            currency = currency,
            grossTotalMinor = grossTotal.toMinorUnits(),
            snapshotJson = canonicalSnapshot,
            snapshotHash = calculatedHash,
            status = FiscalOutboxEntity.STATUS_CLOUD_QUEUED,
            cloudTransactionId = publicId,
            attempts = attempts,
            createdAtEpochMs = now,
            activatedAtEpochMs = now,
            cloudSyncedAtEpochMs = now
        )
    }

    companion object {
        fun fromJson(json: JSONObject): FiscalAgentJob = FiscalAgentJob(
            id = json.optLong("id").takeIf { it > 0 }
                ?: throw FiscalAgentIntegrityException("Fiscal Agent job id missing"),
            publicId = json.optString("public_id").takeIf { it.isNotBlank() }
                ?: throw FiscalAgentIntegrityException("Fiscal Agent job public_id missing"),
            restaurantId = json.optLong("restaurant_id").takeIf { it > 0 }
                ?: throw FiscalAgentIntegrityException("Fiscal Agent job restaurant_id missing"),
            branchId = json.optLong("branch_id").takeIf { it > 0 }
                ?: throw FiscalAgentIntegrityException("Fiscal Agent job branch_id missing"),
            localEventId = json.optNullableString("local_event_id"),
            idempotencyKey = json.optNullableString("idempotency_key"),
            orderId = if (json.isNull("order_id")) null else json.optLong("order_id").takeIf { it > 0 },
            type = json.optString("type", "sale"),
            documentKind = json.optString("document_kind", "vat_receipt"),
            sceEventClass = json.optNullableString("sce_event_class"),
            sceEventType = json.optNullableString("sce_event_type"),
            currency = json.optString("currency", "EUR"),
            grossTotal = json.optString("gross_total", "0"),
            snapshot = json.optJSONObject("snapshot")
                ?: throw FiscalAgentIntegrityException("Fiscal Agent job snapshot missing"),
            snapshotHash = json.optString("snapshot_hash").takeIf { it.length == 64 }
                ?: throw FiscalAgentIntegrityException("Fiscal Agent job snapshot_hash missing"),
            attempts = json.optInt("attempts", 0),
            metadata = json.optJSONObject("metadata")
        )
    }
}

data class FiscalAgentRunResult(
    val processed: Boolean,
    val jobId: Long? = null,
    val publicId: String? = null,
    val receiptNumber: String? = null,
    val duplicate: Boolean = false,
    val replayedFromLocalJournal: Boolean = false
)

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun String.toMinorUnits(): Long = runCatching {
    BigDecimal(this)
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrElse { 0L }

private fun JSONObject.toCanonicalValue(): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>()
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        result[key] = canonicalValue(opt(key))
    }
    return result
}

private fun canonicalValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.toCanonicalValue()
    is JSONArray -> List(value.length()) { index -> canonicalValue(value.opt(index)) }
    is Number, is String, is Boolean -> value
    else -> value.toString()
}
