package be.cookit.pos.android.data.fiscal

import java.io.IOException

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
        var providerCallStarted = false

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

                /*
                 * A15.0F8.6
                 *
                 * Persist a Cloud-side ambiguity barrier BEFORE signSale.
                 * Once this call succeeds, no automatic provider retry is
                 * allowed unless explicit reconciliation occurs.
                 */
                client.providerCallStarted(
                    credentials = credentials,
                    transactionId = job.id,
                    identity = identity
                )

                providerCallStarted = true

                /*
                 * A15.0F8.7.5
                 *
                 * Deterministic process-death window:
                 *
                 *   Cookit Cloud provider-call barrier = durable
                 *   Module2 signSale                  = NOT called yet
                 *
                 * After process restart the system must NOT automatically
                 * call Module2 for this transaction. The Cloud barrier is
                 * intentionally conservative because after a real crash the
                 * restarted process cannot prove whether the provider call
                 * was transmitted.
                 *
                 * Restricted to explicit Module2 TRAINING test jobs.
                 */
                val simulateProcessDeathAfterProviderBarrier =
                    job.metadata
                        ?.optBoolean(
                            "simulate_process_death_after_provider_barrier",
                            false
                        ) == true

                /*
                 * A15.0F8.7.6
                 *
                 * A manual reconciliation adds
                 * previous_attempts_before_manual_retry to the immutable
                 * job metadata. Once present, the F8.7.5 process-death
                 * injector must never fire again for this transaction.
                 *
                 * This makes the crash injector strictly first-attempt-only.
                 */
                val alreadyManuallyReconciled =
                    job.metadata
                        ?.has("previous_attempts_before_manual_retry")
                        == true

                val providerBarrierCrashAllowed =
                    job.metadata?.optBoolean("test_only", false) == true &&
                        settings.isModule2 &&
                        preparedSale?.training == true &&
                        !alreadyManuallyReconciled

                if (
                    simulateProcessDeathAfterProviderBarrier &&
                    providerBarrierCrashAllowed
                ) {
                    /*
                     * Deliberately no PHASE_FDM_CALL before termination:
                     * signSale has not been invoked.
                     *
                     * killProcess + exitProcess makes this a genuine
                     * process-death test rather than an exception/retry test.
                     */
                    android.os.Process.killProcess(
                        android.os.Process.myPid()
                    )
                    kotlin.system.exitProcess(0)
                }

                progress(FiscalAgentRuntimeStateStore.PHASE_FDM_CALL)

                /*
                 * A15.0F8.7.4
                 *
                 * Deterministic watchdog/single-flight test.
                 *
                 * The Cloud-side provider-call ambiguity barrier has already
                 * been persisted at this point, but signSale has NOT yet been
                 * sent to Module2.
                 *
                 * Keeping this coroutine busy for > WATCHDOG_STALL_MS must
                 * cause the watchdog to report:
                 *
                 *   watchdog_busy_stall_no_parallel_restart
                 *
                 * It must never start a second fiscal loop.
                 *
                 * Restricted to explicit Module2 TRAINING test jobs.
                 */
                val providerCallStallMs =
                    job.metadata
                        ?.optLong(
                            "simulate_provider_call_stall_ms",
                            0L
                        )
                        ?.coerceAtLeast(0L)
                        ?: 0L

                val providerCallStallAllowed =
                    job.metadata?.optBoolean("test_only", false) == true &&
                        settings.isModule2 &&
                        preparedSale?.training == true

                if (
                    providerCallStallMs > 0L &&
                    providerCallStallAllowed
                ) {
                    delay(
                        providerCallStallMs.coerceAtMost(150_000L)
                    )
                }

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

                /*
                 * A15.0F8.6 deterministic ambiguity test.
                 *
                 * The provider has returned an accepted sale, but the result
                 * is deliberately discarded before Room persistence.
                 *
                 * This hook is impossible for a LIVE Module2 job.
                 */
                val simulateProviderOutcomeLoss =
                    job.metadata
                        ?.optBoolean(
                            "simulate_provider_outcome_loss",
                            false
                        ) == true

                val explicitTestOnlyJob =
                    job.metadata
                        ?.optBoolean(
                            "test_only",
                            false
                        ) == true

                val ambiguityTestAllowed =
                    settings.isMock
                        || preparedSale?.training == true

                if (
                    simulateProviderOutcomeLoss
                    && explicitTestOnlyJob
                    && ambiguityTestAllowed
                ) {
                    throw IOException(
                        "A15.0F8.6 simulated provider outcome loss after accepted signSale before durable local journal"
                    )
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

            /*
             * A15.0F8.4
             *
             * Deterministic crash/replay test hook.
             *
             * The delay is allowed only for an explicitly test-only job and
             * either:
             *   - the mock provider, or
             *   - a Cloud-prepared Module2 TRAINING request.
             *
             * A LIVE Module2 request can therefore never activate this hook.
             */
            val explicitTestJob =
                job.metadata?.optBoolean("test_only", false) == true

            val deterministicDelayAllowed =
                settings.isMock
                    || preparedSale?.training == true

            if (
                ! replayedFromJournal
                && explicitTestJob
                && deterministicDelayAllowed
            ) {
                val requestedDelay =
                    job.metadata?.optLong(
                        "cloud_submit_delay_ms",
                        0L
                    ) ?: 0L

                val delayMs =
                    requestedDelay.coerceIn(
                        0L,
                        MAX_TEST_CLOUD_SUBMIT_DELAY_MS
                    )

                if (delayMs > 0L) {
                    delay(delayMs)
                }
            }

            /*
             * A15.0F8.7.1
             *
             * Deterministic Cookit Cloud outage AFTER the provider outcome
             * has been durably persisted in Room.
             *
             * This hook is intentionally restricted to:
             *   - an explicitly test-only job,
             *   - Module2,
             *   - a Cloud-prepared TRAINING request.
             *
             * It therefore cannot execute for a LIVE Module2 transaction.
             *
             * On failure, providerOutcomePersisted=true forces
             * RETRY_CLOUD_SYNC. The foreground service will then execute
             * flushPendingOutcome() before processNext(), replaying the
             * durable local result without calling Module2 again.
             */
            val simulateCloudSubmitFailureAfterOutcome =
                job.metadata
                    ?.optBoolean(
                        "simulate_cloud_submit_failure_after_outcome",
                        false
                    ) == true

            val cloudFailureTestAllowed =
                explicitTestJob
                    && settings.isModule2
                    && preparedSale?.training == true

            if (
                ! replayedFromJournal
                && simulateCloudSubmitFailureAfterOutcome
                && cloudFailureTestAllowed
            ) {
                progress(FiscalAgentRuntimeStateStore.PHASE_CLOUD_SUBMIT)

                throw IOException(
                    "A15.0F8.7.1 simulated Cookit Cloud outage after durable local provider outcome"
                )
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

            /*
             * A15.0F8.7.2
             *
             * Deterministic Cookit Cloud ACK outage AFTER:
             *
             *   1. the real Module2 TRAINING result is durable in Room,
             *   2. Cookit Cloud has accepted /submitted,
             *   3. the local outcome state is durable as cloud_submitted.
             *
             * The retry path must therefore:
             *   - never call Module2 again,
             *   - never send /submitted again,
             *   - replay only /acknowledge from the durable journal.
             *
             * LIVE Module2 transactions cannot activate this hook.
             */
            val simulateCloudAckFailureAfterSubmitted =
                job.metadata
                    ?.optBoolean(
                        "simulate_cloud_ack_failure_after_submitted",
                        false
                    ) == true

            val cloudAckFailureTestAllowed =
                explicitTestJob
                    && settings.isModule2
                    && preparedSale?.training == true

            if (
                ! replayedFromJournal
                && simulateCloudAckFailureAfterSubmitted
                && cloudAckFailureTestAllowed
            ) {
                val persistedAfterSubmit =
                    outcomeDao.byTransactionId(job.id)

                if (
                    persistedAfterSubmit?.state
                    != FiscalAgentOutcomeEntity.STATE_CLOUD_SUBMITTED
                ) {
                    throw FiscalAgentIntegrityException(
                        "A15.0F8.7.2 expected durable cloud_submitted state before ACK fault injection"
                    )
                }

                progress(FiscalAgentRuntimeStateStore.PHASE_CLOUD_ACK)

                throw IOException(
                    "A15.0F8.7.2 simulated Cookit Cloud ACK outage after durable cloud_submitted state"
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
                providerCallStarted = providerCallStarted,
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
