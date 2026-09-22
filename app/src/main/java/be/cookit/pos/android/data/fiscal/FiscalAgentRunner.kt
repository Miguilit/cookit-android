package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

class FiscalAgentRunner(
    private val client: FiscalAgentClient,
    private val fdmRuntime: FiscalFdmRuntime,
    private val outcomeDao: FiscalAgentOutcomeDao
) {
    suspend fun flushPendingOutcome(
        credentials: FiscalAgentCredentials,
        identity: FiscalRuntimeIdentity,
        onProgress: (Long, String) -> Unit = { _, _ -> }
    ): FiscalAgentRunResult {
        val outcome = outcomeDao.oldestPending() ?: return FiscalAgentRunResult(processed = false)
        var phase = FiscalAgentRuntimeStateStore.PHASE_REPLAY_LOCAL

        fun progress(nextPhase: String) {
            phase = nextPhase
            onProgress(outcome.transactionId, nextPhase)
        }

        progress(FiscalAgentRuntimeStateStore.PHASE_REPLAY_LOCAL)
        try {
            if (outcome.state == FiscalAgentOutcomeEntity.STATE_PROVIDER_ACCEPTED) {
                progress(FiscalAgentRuntimeStateStore.PHASE_CLOUD_SUBMIT)
                client.submitted(credentials, outcome.transactionId, identity)
                outcomeDao.updateState(
                    outcome.transactionId,
                    FiscalAgentOutcomeEntity.STATE_CLOUD_SUBMITTED,
                    System.currentTimeMillis()
                )
            }

            val fresh = outcomeDao.byTransactionId(outcome.transactionId) ?: outcome
            if (fresh.state != FiscalAgentOutcomeEntity.STATE_CLOUD_ACKED) {
                progress(FiscalAgentRuntimeStateStore.PHASE_CLOUD_ACK)
                client.acknowledge(
                    credentials = credentials,
                    transactionId = fresh.transactionId,
                    identity = identity,
                    success = true,
                    receipt = fresh.toCloudReceipt(replayedFromJournal = true)
                )
                outcomeDao.updateState(
                    fresh.transactionId,
                    FiscalAgentOutcomeEntity.STATE_CLOUD_ACKED,
                    System.currentTimeMillis()
                )
            }

            return FiscalAgentRunResult(
                processed = true,
                jobId = fresh.transactionId,
                publicId = fresh.publicId,
                receiptNumber = fresh.receiptNumber,
                duplicate = fresh.providerDuplicate,
                replayedFromLocalJournal = true
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw FiscalAgentJobExecutionException(
                jobId = outcome.transactionId,
                publicId = outcome.publicId,
                attempts = 0,
                phase = phase,
                claimExpiresAtEpochMs = null,
                providerOutcomePersisted = true,
                providerResponseReceived = true,
                cause = error
            )
        }
    }

    suspend fun processNext(
        credentials: FiscalAgentCredentials,
        identity: FiscalRuntimeIdentity,
        settings: FiscalFdmSettings,
        onProgress: (Long, String) -> Unit = { _, _ -> }
    ): FiscalAgentRunResult {
        val readiness = fdmRuntime.readiness(settings)
        check(readiness.readyForFiscalization) {
            readiness.reason ?: "Fiscal provider adapter is not ready"
        }

        val job = client.nextJob(credentials, identity) ?: return FiscalAgentRunResult(processed = false)
        var phase = FiscalAgentRuntimeStateStore.PHASE_CLAIMED
        var providerResponseReceived = false

        fun progress(nextPhase: String) {
            phase = nextPhase
            onProgress(job.id, nextPhase)
        }

        progress(FiscalAgentRuntimeStateStore.PHASE_CLAIMED)

        try {
            val event = job.asProviderEvent(identity)

            val preparedSale =
                if (settings.isModule2) {
                    job.preparedSignSale(identity)
                } else {
                    null
                }
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

                progress(FiscalAgentRuntimeStateStore.PHASE_FDM_CALL)

                val envelope = fdmRuntime.submitSale(
                    settings = settings,
                    event = event,
                    preparedSale = preparedSale,
                    headers = mockHeaders
                )

                val sale = envelope
                    .optJSONObject("data")
                    ?.optJSONObject("signSale")
                    ?: throw FdmGraphqlException(
                        "FDM response does not contain data.signSale",
                        envelope.toString()
                    )

                providerResponseReceived = true

                val receiptNumber: String
                val signature: String?
                val verificationCode: String?
                val providerReference: String?
                val providerDuplicate: Boolean

                if (settings.isModule2) {
                    val expectedTicket =
                        preparedSale?.sequenceNumber
                            ?: throw FiscalAgentIntegrityException(
                                "Prepared Module2 fiscal sequence missing"
                            )

                    val actualTicket =
                        if (
                            sale.has("posFiscalTicketNo") &&
                            !sale.isNull("posFiscalTicketNo")
                        ) {
                            runCatching {
                                sale.getInt("posFiscalTicketNo")
                            }.getOrNull()
                        } else {
                            null
                        }
                            ?: throw FdmGraphqlException(
                                "Module2 posFiscalTicketNo missing",
                                envelope.toString()
                            )

                    if (actualTicket != expectedTicket) {
                        throw FiscalAgentIntegrityException(
                            "Module2 receipt ticket mismatch: " +
                                "expected=$expectedTicket " +
                                "actual=$actualTicket"
                        )
                    }

                    receiptNumber = actualTicket.toString()

                    signature =
                        sale.optString("digitalSignature")
                            .takeIf { it.isNotBlank() }

                    verificationCode =
                        sale.optString("shortSignature")
                            .takeIf { it.isNotBlank() }

                    providerReference =
                        sale.optJSONObject("fdmRef")
                            ?.optString("fdmId")
                            ?.takeIf { it.isNotBlank() }

                    providerDuplicate = false

                } else {
                    /*
                     * Existing Cookit Mock contract remains unchanged.
                     */
                    if (!sale.optBoolean("success", false)) {
                        throw FdmGraphqlException(
                            "FDM signSale did not succeed",
                            envelope.toString()
                        )
                    }

                    receiptNumber =
                        sale.optString("receiptNumber")
                            .takeIf { it.isNotBlank() }
                            ?: throw FdmGraphqlException(
                                "FDM receipt number missing",
                                envelope.toString()
                            )

                    signature =
                        sale.optString("signature")
                            .takeIf { it.isNotBlank() }

                    verificationCode =
                        sale.optString("verificationCode")
                            .takeIf { it.isNotBlank() }

                    providerReference =
                        sale.optString("providerReference")
                            .takeIf { it.isNotBlank() }

                    providerDuplicate =
                        sale.optBoolean("duplicate", false)
                }

                val now = System.currentTimeMillis()

                val created = FiscalAgentOutcomeEntity(
                    transactionId = job.id,
                    publicId = job.publicId,
                    idempotencyKey = event.idempotencyKey,
                    snapshotHash = event.snapshotHash,
                    provider = settings.provider,
                    receiptNumber = receiptNumber,
                    signature = signature,
                    verificationCode = verificationCode,
                    providerReference = providerReference,
                    rawResponseJson = envelope.toString(),
                    providerDuplicate = providerDuplicate,
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

            progress(
                if (replayedFromJournal) FiscalAgentRuntimeStateStore.PHASE_REPLAY_LOCAL
                else FiscalAgentRuntimeStateStore.PHASE_PROVIDER_ACCEPTED
            )

            if (!replayedFromJournal && settings.isMock && job.metadata?.optBoolean("test_only", false) == true) {
                val requestedDelay = job.metadata.optLong("cloud_submit_delay_ms", 0L)
                val delayMs = requestedDelay.coerceIn(0L, MAX_TEST_CLOUD_SUBMIT_DELAY_MS)
                if (delayMs > 0L) delay(delayMs)
            }

            if (outcome.state == FiscalAgentOutcomeEntity.STATE_PROVIDER_ACCEPTED) {
                progress(FiscalAgentRuntimeStateStore.PHASE_CLOUD_SUBMIT)
                client.submitted(credentials, job.id, identity)
                outcomeDao.updateState(
                    job.id,
                    FiscalAgentOutcomeEntity.STATE_CLOUD_SUBMITTED,
                    System.currentTimeMillis()
                )
            }

            val freshOutcome = outcomeDao.byTransactionId(job.id) ?: outcome
            if (freshOutcome.state != FiscalAgentOutcomeEntity.STATE_CLOUD_ACKED) {
                progress(FiscalAgentRuntimeStateStore.PHASE_CLOUD_ACK)
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val providerOutcomePersisted = runCatching { outcomeDao.byTransactionId(job.id) != null }.getOrDefault(false)
            throw FiscalAgentJobExecutionException(
                jobId = job.id,
                publicId = job.publicId,
                attempts = job.attempts,
                phase = phase,
                claimExpiresAtEpochMs = job.claimExpiresAtEpochMs,
                providerOutcomePersisted = providerOutcomePersisted,
                providerResponseReceived = providerResponseReceived,
                cause = error
            )
        }
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
        private const val MAX_TEST_CLOUD_SUBMIT_DELAY_MS = 60_000L

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
