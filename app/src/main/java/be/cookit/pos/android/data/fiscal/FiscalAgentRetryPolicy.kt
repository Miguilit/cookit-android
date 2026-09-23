package be.cookit.pos.android.data.fiscal

import java.io.IOException
import kotlin.math.max

class FiscalAgentJobExecutionException(
    val jobId: Long,
    val publicId: String,
    val attempts: Int,
    val phase: String,
    val claimExpiresAtEpochMs: Long?,
    val providerOutcomePersisted: Boolean,
    val providerResponseReceived: Boolean,
    val providerCallStarted: Boolean = false,
    cause: Throwable
) : Exception(cause.message, cause)

enum class FiscalFailureClass {
    TRANSIENT,
    PERMANENT,
    CONFIGURATION,
    AUTH,
    PROVIDER_REJECTED,
    NETWORK,
    UNKNOWN
}

enum class FiscalRetryDisposition {
    TERMINAL_FAILURE,
    RETRY_PROVIDER,
    RETRY_CLOUD_SYNC,
    MANUAL_HOLD
}

data class FiscalRetryDecision(
    val disposition: FiscalRetryDisposition,
    val failureClass: FiscalFailureClass,
    val reasonCode: String,
    val retryAtEpochMs: Long? = null,
    val errorForCloud: String
)

class FiscalAgentRetryPolicy {
    fun decide(
        failure: FiscalAgentJobExecutionException,
        nowEpochMs: Long = System.currentTimeMillis()
    ): FiscalRetryDecision {
        val error = failure.cause ?: failure
        val errorText = error.message.orEmpty().ifBlank { error::class.java.simpleName }

        // Once the FDM result is durable locally, no decision below may call the provider again.
        if (failure.providerOutcomePersisted) {
            return decideCloudSyncOnly(error, errorText, nowEpochMs)
        }

        if (error is FiscalAgentIntegrityException) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                failureClass = FiscalFailureClass.PERMANENT,
                reasonCode = "integrity_hold",
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FiscalProviderMappingUnavailable) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                failureClass = FiscalFailureClass.CONFIGURATION,
                reasonCode = "provider_mapping_hold",
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FiscalAgentTransportException) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.RETRY_CLOUD_SYNC,
                failureClass = FiscalFailureClass.NETWORK,
                reasonCode = "cloud_transport_retry",
                retryAtEpochMs = nowEpochMs + CLOUD_SYNC_BACKOFF_MS,
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FiscalAgentException) {
            return decideCloudHttp(error, nowEpochMs)
        }

        if (error is FdmGraphqlException) {
            return decideGraphql(failure, error, nowEpochMs)
        }

        if (error is IOException) {
            if (failure.providerCallStarted) {
                return ambiguousProviderHold(
                    failureClass = FiscalFailureClass.NETWORK,
                    reasonCode = "fdm_transport_ambiguous_hold",
                    message = errorText
                )
            }

            return retryAmbiguousProvider(
                failure = failure,
                failureClass = FiscalFailureClass.NETWORK,
                nowEpochMs = nowEpochMs,
                reasonCode = "fdm_transport_pre_call_retry",
                message = errorText
            )
        }

        if (failure.providerCallStarted) {
            return ambiguousProviderHold(
                failureClass = FiscalFailureClass.UNKNOWN,
                reasonCode = "fdm_outcome_ambiguous_hold",
                message = errorText
            )
        }

        return FiscalRetryDecision(
            disposition = FiscalRetryDisposition.MANUAL_HOLD,
            failureClass = FiscalFailureClass.UNKNOWN,
            reasonCode = "unclassified_hold",
            errorForCloud = errorText.take(MAX_ERROR_LENGTH)
        )
    }

    private fun decideCloudSyncOnly(
        error: Throwable,
        errorText: String,
        nowEpochMs: Long
    ): FiscalRetryDecision = when (error) {
        is FiscalAgentTransportException, is IOException -> FiscalRetryDecision(
            disposition = FiscalRetryDisposition.RETRY_CLOUD_SYNC,
            failureClass = FiscalFailureClass.NETWORK,
            reasonCode = "cloud_sync_network_retry",
            retryAtEpochMs = nowEpochMs + CLOUD_SYNC_BACKOFF_MS,
            errorForCloud = errorText.take(MAX_ERROR_LENGTH)
        )
        is FiscalAgentException -> decideCloudHttp(error, nowEpochMs)
        else -> FiscalRetryDecision(
            disposition = FiscalRetryDisposition.MANUAL_HOLD,
            failureClass = FiscalFailureClass.UNKNOWN,
            reasonCode = "cloud_sync_unknown_hold",
            errorForCloud = errorText.take(MAX_ERROR_LENGTH)
        )
    }

    private fun decideCloudHttp(
        error: FiscalAgentException,
        nowEpochMs: Long
    ): FiscalRetryDecision {
        val errorText = error.message.orEmpty().ifBlank { "Cookit Cloud error" }
        val status = error.statusCode

        if (status == 401 || status == 403) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                failureClass = FiscalFailureClass.AUTH,
                reasonCode = "cloud_auth_hold",
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (status == 408 || status == 425 || status == 429 || (status != null && status in 500..599)) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.RETRY_CLOUD_SYNC,
                failureClass = FiscalFailureClass.TRANSIENT,
                reasonCode = "cloud_transient_retry",
                retryAtEpochMs = nowEpochMs + CLOUD_SYNC_BACKOFF_MS,
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        return FiscalRetryDecision(
            disposition = FiscalRetryDisposition.MANUAL_HOLD,
            failureClass = FiscalFailureClass.PERMANENT,
            reasonCode = "cloud_http_hold",
            errorForCloud = errorText.take(MAX_ERROR_LENGTH)
        )
    }

    private fun decideGraphql(
        failure: FiscalAgentJobExecutionException,
        error: FdmGraphqlException,
        nowEpochMs: Long
    ): FiscalRetryDecision {
        val message = error.message.orEmpty()
        val normalized = message.lowercase()
        val body = error.responseBody.lowercase()
        val status = error.httpStatus

        if (status == 401 || status == 403) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                failureClass = FiscalFailureClass.AUTH,
                reasonCode = "fdm_auth_hold",
                errorForCloud = message.ifBlank { "FDM authorization failure" }.take(MAX_ERROR_LENGTH)
            )
        }

        if (status == 404) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                failureClass = FiscalFailureClass.CONFIGURATION,
                reasonCode = "fdm_endpoint_hold",
                errorForCloud = message.ifBlank { "FDM endpoint unavailable" }.take(MAX_ERROR_LENGTH)
            )
        }

        if (status == 400 || status == 409 || status == 422) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.TERMINAL_FAILURE,
                failureClass = FiscalFailureClass.PROVIDER_REJECTED,
                reasonCode = "fdm_permanent_rejection",
                errorForCloud = message.ifBlank { "FDM permanent rejection" }.take(MAX_ERROR_LENGTH)
            )
        }

        if (status == 408 || status == 425 || status == 429 || (status != null && status in 500..599)) {
            return if (failure.providerCallStarted) {
                ambiguousProviderHold(
                    failureClass = FiscalFailureClass.TRANSIENT,
                    reasonCode = "fdm_transient_ambiguous_hold",
                    message = message
                )
            } else {
                retryAmbiguousProvider(
                    failure = failure,
                    failureClass = FiscalFailureClass.TRANSIENT,
                    nowEpochMs = nowEpochMs,
                    reasonCode = "fdm_transient_pre_call_retry",
                    message = message
                )
            }
        }

        val explicitProviderRejection =
            normalized.contains("did not succeed") ||
                body.contains("\"errors\"")

        if (explicitProviderRejection) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.TERMINAL_FAILURE,
                failureClass = FiscalFailureClass.PROVIDER_REJECTED,
                reasonCode = "fdm_permanent_rejection",
                errorForCloud = message.ifBlank { "FDM permanent rejection" }.take(MAX_ERROR_LENGTH)
            )
        }

        val ambiguous =
            normalized.contains("malformed json") ||
                normalized.contains("does not contain data.signsale") ||
                normalized.contains("receipt number missing") ||
                failure.providerResponseReceived

        if (ambiguous) {
            return if (failure.providerCallStarted) {
                ambiguousProviderHold(
                    failureClass = FiscalFailureClass.UNKNOWN,
                    reasonCode = "fdm_response_ambiguous_hold",
                    message = message
                )
            } else {
                retryAmbiguousProvider(
                    failure = failure,
                    failureClass = FiscalFailureClass.UNKNOWN,
                    nowEpochMs = nowEpochMs,
                    reasonCode = "fdm_ambiguous_pre_call_retry",
                    message = message
                )
            }
        }

        return FiscalRetryDecision(
            disposition = FiscalRetryDisposition.MANUAL_HOLD,
            failureClass = FiscalFailureClass.UNKNOWN,
            reasonCode = "fdm_unclassified_hold",
            errorForCloud = message.ifBlank { "Unclassified FDM failure" }.take(MAX_ERROR_LENGTH)
        )
    }

    private fun ambiguousProviderHold(
        failureClass: FiscalFailureClass,
        reasonCode: String,
        message: String
    ): FiscalRetryDecision = FiscalRetryDecision(
        disposition = FiscalRetryDisposition.MANUAL_HOLD,
        failureClass = failureClass,
        reasonCode = reasonCode,
        retryAtEpochMs = null,
        errorForCloud =
            message
                .ifBlank {
                    "Provider call started but no durable outcome exists"
                }
                .take(MAX_ERROR_LENGTH)
    )

    private fun retryAmbiguousProvider(
        failure: FiscalAgentJobExecutionException,
        failureClass: FiscalFailureClass,
        nowEpochMs: Long,
        reasonCode: String,
        message: String
    ): FiscalRetryDecision {
        if (failure.attempts >= MAX_PROVIDER_ATTEMPTS) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                failureClass = failureClass,
                reasonCode = "provider_retry_exhausted",
                errorForCloud = message.ifBlank { reasonCode }.take(MAX_ERROR_LENGTH)
            )
        }

        val base = PROVIDER_BACKOFF_MS[(failure.attempts - 1).coerceIn(0, PROVIDER_BACKOFF_MS.lastIndex)]
        return FiscalRetryDecision(
            disposition = FiscalRetryDisposition.RETRY_PROVIDER,
            failureClass = failureClass,
            reasonCode = reasonCode,
            retryAtEpochMs = nextRetryAt(failure, nowEpochMs, base),
            errorForCloud = message.ifBlank { reasonCode }.take(MAX_ERROR_LENGTH)
        )
    }

    private fun nextRetryAt(
        failure: FiscalAgentJobExecutionException,
        nowEpochMs: Long,
        baseDelayMs: Long
    ): Long {
        val leaseSafeEpoch = failure.claimExpiresAtEpochMs?.plus(LEASE_GRACE_MS) ?: 0L
        return max(nowEpochMs + baseDelayMs, leaseSafeEpoch)
    }

    companion object {
        const val MAX_PROVIDER_ATTEMPTS = 3
        private const val LEASE_GRACE_MS = 1_500L
        private const val MAX_ERROR_LENGTH = 1_800
        private const val CLOUD_SYNC_BACKOFF_MS = 30_000L
        private val PROVIDER_BACKOFF_MS = longArrayOf(15_000L, 30_000L, 60_000L)
    }
}
