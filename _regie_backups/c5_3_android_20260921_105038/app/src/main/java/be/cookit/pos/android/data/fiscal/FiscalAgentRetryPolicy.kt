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
    cause: Throwable
) : Exception(cause.message, cause)

enum class FiscalRetryDisposition {
    TERMINAL_FAILURE,
    RETRY_PROVIDER,
    RETRY_CLOUD_SYNC,
    MANUAL_HOLD
}

data class FiscalRetryDecision(
    val disposition: FiscalRetryDisposition,
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

        if (failure.providerOutcomePersisted) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.RETRY_CLOUD_SYNC,
                reasonCode = "cloud_sync_pending",
                retryAtEpochMs = nowEpochMs + CLOUD_SYNC_BACKOFF_MS,
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FiscalAgentIntegrityException) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                reasonCode = "integrity_hold",
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FiscalProviderMappingUnavailable) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                reasonCode = "provider_mapping_hold",
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FiscalAgentTransportException || error is FiscalAgentException) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.RETRY_CLOUD_SYNC,
                reasonCode = "cloud_transport_retry",
                retryAtEpochMs = nowEpochMs + CLOUD_SYNC_BACKOFF_MS,
                errorForCloud = errorText.take(MAX_ERROR_LENGTH)
            )
        }

        if (error is FdmGraphqlException) {
            return decideGraphql(failure, error, nowEpochMs)
        }

        if (error is IOException) {
            return retryAmbiguousProvider(failure, nowEpochMs, "fdm_transport_ambiguous", errorText)
        }

        return FiscalRetryDecision(
            disposition = FiscalRetryDisposition.MANUAL_HOLD,
            reasonCode = "unclassified_hold",
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

        val explicitPermanent =
            normalized.contains("http 400") ||
                normalized.contains("http 401") ||
                normalized.contains("http 403") ||
                normalized.contains("http 404") ||
                normalized.contains("http 409") ||
                normalized.contains("http 422") ||
                normalized.contains("did not succeed") ||
                body.contains("\"errors\"")

        if (explicitPermanent) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.TERMINAL_FAILURE,
                reasonCode = "fdm_permanent_rejection",
                errorForCloud = message.ifBlank { "FDM permanent rejection" }.take(MAX_ERROR_LENGTH)
            )
        }

        val ambiguous =
            normalized.contains("http 5") ||
                normalized.contains("malformed json") ||
                normalized.contains("does not contain data.signsale") ||
                normalized.contains("receipt number missing") ||
                failure.providerResponseReceived

        return if (ambiguous) {
            retryAmbiguousProvider(failure, nowEpochMs, "fdm_ambiguous_retry", message)
        } else {
            FiscalRetryDecision(
                disposition = FiscalRetryDisposition.TERMINAL_FAILURE,
                reasonCode = "fdm_permanent_rejection",
                errorForCloud = message.ifBlank { "FDM permanent rejection" }.take(MAX_ERROR_LENGTH)
            )
        }
    }

    private fun retryAmbiguousProvider(
        failure: FiscalAgentJobExecutionException,
        nowEpochMs: Long,
        reasonCode: String,
        message: String
    ): FiscalRetryDecision {
        if (failure.attempts >= MAX_PROVIDER_ATTEMPTS) {
            return FiscalRetryDecision(
                disposition = FiscalRetryDisposition.MANUAL_HOLD,
                reasonCode = "provider_retry_exhausted",
                errorForCloud = message.ifBlank { reasonCode }.take(MAX_ERROR_LENGTH)
            )
        }

        val base = PROVIDER_BACKOFF_MS[(failure.attempts - 1).coerceIn(0, PROVIDER_BACKOFF_MS.lastIndex)]
        return FiscalRetryDecision(
            disposition = FiscalRetryDisposition.RETRY_PROVIDER,
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
