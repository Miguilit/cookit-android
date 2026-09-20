package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Cloud -> Android -> local FDM -> Cloud runner.
 *
 * A14.5.3 adds a durable provider-outcome journal. A valid FDM receipt is committed locally before
 * any cloud /submitted or /acknowledge call. A later lease retry can therefore replay the cloud
 * acknowledgement without asking the FDM to fiscalize the same event again.
 */
class FiscalAgentRunner(
    private val client: FiscalAgentClient,
    private val fdmRuntime: FiscalFdmRuntime,
    private val outcomeDao: FiscalAgentOutcomeDao
) {
    suspend fun processNext(
        credentials: FiscalAgentCredentials,
        identity: FiscalRuntimeIdentity,
        settings: FiscalFdmSettings
    ): FiscalAgentRunResult {
        val readiness = fdmRuntime.readiness(settings)
        check(readiness.readyForFiscalization) {
            readiness.reason ?: "Fiscal provider adapter is not ready"
        }

        val job = client.nextJob(credentials, identity) ?: return FiscalAgentRunResult(processed = false)
        val event = job.asProviderEvent(identity)

        val existing = outcomeDao.byTransactionId(job.id)
        val replayedFromJournal = existing != null
        val outcome = existing?.also { validateStoredOutcome(it, job) } ?: run {
            val metadata = job.metadata
            val mockScenario = if (settings.isMock && metadata?.optBoolean("test_only", false) == true) {
                metadata.optString("scenario")
                    .trim()
                    .lowercase()
                    .takeIf { it in ALLOWED_MOCK_SCENARIOS }
            } else {
                null
            }
            val mockHeaders = mockScenario?.let { mapOf("X-Cookit-Mock-Scenario" to it) }.orEmpty()

            val envelope = fdmRuntime.submitSale(settings, event, headers = mockHeaders)
            val sale = envelope.optJSONObject("data")?.optJSONObject("signSale")
                ?: throw FdmGraphqlException("FDM response does not contain data.signSale", envelope.toString())
            if (!sale.optBoolean("success", false)) {
                throw FdmGraphqlException("FDM signSale did not succeed", envelope.toString())
            }

            val receiptNumber = sale.optString("receiptNumber").takeIf { it.isNotBlank() }
                ?: throw FdmGraphqlException("FDM receipt number missing", envelope.toString())
            val now = System.currentTimeMillis()
            val created = FiscalAgentOutcomeEntity(
                transactionId = job.id,
                publicId = job.publicId,
                idempotencyKey = event.idempotencyKey,
                snapshotHash = event.snapshotHash,
                provider = settings.provider,
                receiptNumber = receiptNumber,
                signature = sale.optString("signature").takeIf { it.isNotBlank() },
                verificationCode = sale.optString("verificationCode").takeIf { it.isNotBlank() },
                providerReference = sale.optString("providerReference").takeIf { it.isNotBlank() },
                rawResponseJson = envelope.toString(),
                providerDuplicate = sale.optBoolean("duplicate", false),
                state = FiscalAgentOutcomeEntity.STATE_PROVIDER_ACCEPTED,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )

            val inserted = outcomeDao.insert(created)
            if (inserted == -1L) {
                outcomeDao.byTransactionId(job.id)?.also { validateStoredOutcome(it, job) }
                    ?: throw FiscalAgentIntegrityException("Unable to persist/reload provider outcome for transaction ${job.id}")
            } else {
                created
            }
        }

        // Test-only deterministic window: the provider result is already durable locally, but the
        // cloud transition is intentionally delayed so Wi-Fi can be disabled after FDM acceptance.
        if (!replayedFromJournal && settings.isMock && job.metadata?.optBoolean("test_only", false) == true) {
            val requestedDelay = job.metadata.optLong("cloud_submit_delay_ms", 0L)
            val delayMs = requestedDelay.coerceIn(0L, MAX_TEST_CLOUD_SUBMIT_DELAY_MS)
            if (delayMs > 0L) delay(delayMs)
        }

        if (outcome.state == FiscalAgentOutcomeEntity.STATE_PROVIDER_ACCEPTED) {
            client.submitted(credentials, job.id, identity)
            outcomeDao.updateState(
                job.id,
                FiscalAgentOutcomeEntity.STATE_CLOUD_SUBMITTED,
                System.currentTimeMillis()
            )
        }

        val freshOutcome = outcomeDao.byTransactionId(job.id) ?: outcome
        if (freshOutcome.state != FiscalAgentOutcomeEntity.STATE_CLOUD_ACKED) {
            client.acknowledge(
                credentials = credentials,
                transactionId = job.id,
                identity = identity,
                success = true,
                receipt = freshOutcome.toCloudReceipt(replayedFromJournal)
            )
            outcomeDao.updateState(
                job.id,
                FiscalAgentOutcomeEntity.STATE_CLOUD_ACKED,
                System.currentTimeMillis()
            )
        }

        return FiscalAgentRunResult(
            processed = true,
            jobId = job.id,
            publicId = job.publicId,
            receiptNumber = freshOutcome.receiptNumber,
            duplicate = freshOutcome.providerDuplicate,
            replayedFromLocalJournal = replayedFromJournal
        )
    }

    private fun validateStoredOutcome(outcome: FiscalAgentOutcomeEntity, job: FiscalAgentJob) {
        if (outcome.publicId != job.publicId || !outcome.snapshotHash.equals(job.snapshotHash, ignoreCase = true)) {
            throw FiscalAgentIntegrityException(
                "Stored provider outcome does not match cloud transaction ${job.id}"
            )
        }
        val expectedKey = job.idempotencyKey?.takeIf { it.isNotBlank() } ?: "cloud:${job.publicId}"
        if (outcome.idempotencyKey != expectedKey) {
            throw FiscalAgentIntegrityException(
                "Stored provider outcome idempotency key mismatch for transaction ${job.id}"
            )
        }
    }

    private fun FiscalAgentOutcomeEntity.toCloudReceipt(replayedFromJournal: Boolean): JSONObject {
        return JSONObject()
            .put("receipt_number", receiptNumber)
            .put("signature", signature)
            .put("verification_code", verificationCode)
            .put("provider_reference", providerReference)
            .put("raw_response", rawResponseJson)
            .put(
                "response_meta",
                JSONObject()
                    .put("provider", provider)
                    .put("duplicate", providerDuplicate)
                    .put("android_agent", true)
                    .put("test_only", provider == FiscalFdmSettings.PROVIDER_MOCK)
                    .put("local_outcome_journal", true)
                    .put("replayed_from_local_journal", replayedFromJournal)
            )
    }

    companion object {
        private const val MAX_TEST_CLOUD_SUBMIT_DELAY_MS = 15_000L

        private val ALLOWED_MOCK_SCENARIOS = setOf(
            "success",
            "lost_response",
            "lost_response_once",
            "graphql_error",
            "http_500",
            "malformed",
            "auth_required",
            "slow",
            "timeout"
        )
    }
}
