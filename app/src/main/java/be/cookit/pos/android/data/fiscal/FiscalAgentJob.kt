package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

class FiscalAgentIntegrityException(message: String) : IllegalStateException(message)

/**
 * Immutable A15.0F8.2 request prepared by Cookit Cloud.
 *
 * requestCanonicalJson is opaque transport evidence. Android validates it,
 * but MUST NOT rebuild or reserialize it before Module2 submission.
 */
data class PreparedFiscalSignSale(
    val requestCanonicalJson: String,
    val requestSha256: String,
    val sequenceNumber: Int,
    val training: Boolean
)

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
    val snapshotCanonicalJson: String,
    val attempts: Int,
    val claimExpiresAtEpochMs: Long?,
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

    fun preparedSignSale(identity: FiscalRuntimeIdentity): PreparedFiscalSignSale {
        validateScope(identity)
        validateSnapshotIntegrity()

        val schema = snapshot.optString("schema")
        if (schema != "cookit.be.fiscal.local-agent-job.v1") {
            throw FiscalAgentIntegrityException(
                "Unsupported prepared fiscal snapshot schema for transaction $id"
            )
        }

        val job = snapshot.optJSONObject("job")
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal snapshot job contract missing for transaction $id"
            )

        if (job.optString("operation") != "signSale") {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal operation is not signSale for transaction $id"
            )
        }

        val jobTraining = runCatching {
            job.getBoolean("training")
        }.getOrElse {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal job training flag missing for transaction $id"
            )
        }

        /*
         * F8.2 deliberately opens only the remote Module2 TRAINING path.
         * Production sale activation requires a separate recovery design for
         * the ambiguous case where the FDM accepts a request but the HTTP
         * response is lost before Android can journal the outcome.
         */
        if (!jobTraining) {
            throw FiscalAgentIntegrityException(
                "A15.0F8.2 Module2 transport accepts TRAINING jobs only"
            )
        }

        val sequence = job.optInt("sequence_number", 0)
        if (sequence <= 0) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal sequence missing for transaction $id"
            )
        }

        val requestCanonicalJson = snapshot
            .optString("request_canonical_json")
            .takeIf { it.isNotBlank() }
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal canonical request missing for transaction $id"
            )

        val expected = snapshot.optJSONObject("expected")
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal expected integrity contract missing for transaction $id"
            )

        val expectedSha = expected
            .optString("request_sha256")
            .lowercase()
            .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal request SHA-256 missing for transaction $id"
            )

        val actualSha = FiscalCanonicalJson.sha256Hex(requestCanonicalJson)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal request SHA-256 mismatch for transaction $id"
            )
        }

        val canonicalRequest = try {
            JSONObject(requestCanonicalJson)
        } catch (error: Throwable) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal canonical request is malformed for transaction $id"
            )
        }

        val query = canonicalRequest
            .optString("query")
            .takeIf { it.isNotBlank() }
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal GraphQL query missing for transaction $id"
            )

        val storedRequest = snapshot.optJSONObject("request")
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal request object missing for transaction $id"
            )

        if (storedRequest.optString("query") != query) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal query semantic mismatch for transaction $id"
            )
        }

        val variables = canonicalRequest.optJSONObject("variables")
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal GraphQL variables missing for transaction $id"
            )

        val requestTraining = runCatching {
            variables.getBoolean("training")
        }.getOrElse {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal request training flag missing for transaction $id"
            )
        }

        if (!requestTraining || requestTraining != jobTraining) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal training contract mismatch for transaction $id"
            )
        }

        val data = variables.optJSONObject("data")
            ?: throw FiscalAgentIntegrityException(
                "Prepared fiscal SaleInput missing for transaction $id"
            )

        val ticketNumber = data.optInt("posFiscalTicketNo", 0)
        if (ticketNumber != sequence) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal ticket/sequence mismatch for transaction $id"
            )
        }

        val storedVariables = storedRequest.optJSONObject("variables")
            ?: throw FiscalAgentIntegrityException(
                "Stored fiscal request variables missing for transaction $id"
            )

        val storedTraining = runCatching {
            storedVariables.getBoolean("training")
        }.getOrElse {
            throw FiscalAgentIntegrityException(
                "Stored fiscal request training flag missing for transaction $id"
            )
        }

        val storedTicket = storedVariables
            .optJSONObject("data")
            ?.optInt("posFiscalTicketNo", 0)
            ?: 0

        if (storedTraining != requestTraining || storedTicket != ticketNumber) {
            throw FiscalAgentIntegrityException(
                "Prepared fiscal request semantic proof mismatch for transaction $id"
            )
        }

        return PreparedFiscalSignSale(
            requestCanonicalJson = requestCanonicalJson,
            requestSha256 = actualSha,
            sequenceNumber = sequence,
            training = true
        )
    }

    fun asProviderEvent(identity: FiscalRuntimeIdentity): FiscalOutboxEntity {
        validateScope(identity)
        val canonicalSnapshot = validateSnapshotIntegrity()
        val calculatedHash =
            FiscalCanonicalJson.sha256Hex(canonicalSnapshot)

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

    private fun validateSnapshotIntegrity(): String {
        if (snapshotCanonicalJson.isBlank()) {
            throw FiscalAgentIntegrityException(
                "Cloud fiscal canonical snapshot missing for transaction $id"
            )
        }

        val calculatedHash =
            FiscalCanonicalJson.sha256Hex(snapshotCanonicalJson)

        if (!calculatedHash.equals(snapshotHash, ignoreCase = true)) {
            throw FiscalAgentIntegrityException(
                "Cloud fiscal snapshot SHA-256 mismatch for transaction $id"
            )
        }

        return snapshotCanonicalJson
    }

    companion object {
        fun fromJson(json: JSONObject): FiscalAgentJob {
            val snapshotHash =
                json.optString("snapshot_hash")
                    .takeIf {
                        it.length == 64 &&
                            it.all { character ->
                                character.isDigit() ||
                                    character.lowercaseChar() in 'a'..'f'
                            }
                    }
                    ?: throw FiscalAgentIntegrityException(
                        "Fiscal Agent job snapshot_hash missing"
                    )

            val snapshotCanonicalJson =
                json.optString("snapshot_canonical_json")
                    .takeIf { it.isNotBlank() }
                    ?: throw FiscalAgentIntegrityException(
                        "Fiscal Agent job snapshot_canonical_json missing"
                    )

            val calculatedHash =
                FiscalCanonicalJson.sha256Hex(
                    snapshotCanonicalJson
                )

            if (!calculatedHash.equals(
                    snapshotHash,
                    ignoreCase = true
                )
            ) {
                throw FiscalAgentIntegrityException(
                    "Cloud fiscal snapshot SHA-256 mismatch before parsing"
                )
            }

            /*
             * The authoritative structured snapshot is parsed FROM the
             * canonical bytes whose SHA-256 has just been verified.
             *
             * The sibling JSON object in the HTTP envelope is deliberately
             * not authoritative for fiscal execution.
             */
            val verifiedSnapshot =
                runCatching {
                    JSONObject(snapshotCanonicalJson)
                }.getOrElse {
                    throw FiscalAgentIntegrityException(
                        "Cloud fiscal canonical snapshot is invalid JSON"
                    )
                }

            return FiscalAgentJob(
                id = json.optLong("id").takeIf { it > 0 }
                    ?: throw FiscalAgentIntegrityException(
                        "Fiscal Agent job id missing"
                    ),

                publicId =
                    json.optString("public_id")
                        .takeIf { it.isNotBlank() }
                        ?: throw FiscalAgentIntegrityException(
                            "Fiscal Agent job public_id missing"
                        ),

                restaurantId =
                    json.optLong("restaurant_id")
                        .takeIf { it > 0 }
                        ?: throw FiscalAgentIntegrityException(
                            "Fiscal Agent job restaurant_id missing"
                        ),

                branchId =
                    json.optLong("branch_id")
                        .takeIf { it > 0 }
                        ?: throw FiscalAgentIntegrityException(
                            "Fiscal Agent job branch_id missing"
                        ),

                localEventId =
                    json.optNullableString("local_event_id"),

                idempotencyKey =
                    json.optNullableString("idempotency_key"),

                orderId =
                    if (json.isNull("order_id")) {
                        null
                    } else {
                        json.optLong("order_id")
                            .takeIf { it > 0 }
                    },

                type =
                    json.optString("type", "sale"),

                documentKind =
                    json.optString(
                        "document_kind",
                        "vat_receipt"
                    ),

                sceEventClass =
                    json.optNullableString(
                        "sce_event_class"
                    ),

                sceEventType =
                    json.optNullableString(
                        "sce_event_type"
                    ),

                currency =
                    json.optString("currency", "EUR"),

                grossTotal =
                    json.optString("gross_total", "0"),

                snapshot =
                    verifiedSnapshot,

                snapshotHash =
                    snapshotHash,

                snapshotCanonicalJson =
                    snapshotCanonicalJson,

                attempts =
                    json.optInt("attempts", 0),

                claimExpiresAtEpochMs =
                    json.optNullableString(
                        "claim_expires_at"
                    )?.toEpochMsOrNull(),

                metadata =
                    json.optJSONObject("metadata")
            )
        }
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

private fun String.toEpochMsOrNull(): Long? = runCatching {
    OffsetDateTime.parse(this).toInstant().toEpochMilli()
}.getOrNull()

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
