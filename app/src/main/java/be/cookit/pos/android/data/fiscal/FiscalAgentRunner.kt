package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import org.json.JSONObject

/**
 * C3 foreground Fiscal Agent worker.
 *
 * A single iteration claims at most one cloud transaction, validates tenant/branch + snapshot
 * integrity, submits it to the currently selected provider adapter, marks it submitted in Cookit
 * Cloud, then acknowledges the normalized provider receipt. The production Checkbox adapter remains
 * fail-closed until its certified mapping is installed; therefore C3 can execute only against the
 * debug Mock FDM today.
 */
class FiscalAgentRunner(
    private val client: FiscalAgentClient,
    private val fdmRuntime: FiscalFdmRuntime
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
        val duplicate = sale.optBoolean("duplicate", false)

        // The job reached the provider and produced a valid normalized response.
        // Persist the explicit submitted state before the final cloud acknowledgement.
        client.submitted(credentials, job.id, identity)

        val receipt = JSONObject()
            .put("receipt_number", receiptNumber)
            .put("signature", sale.optString("signature"))
            .put("verification_code", sale.optString("verificationCode"))
            .put("provider_reference", sale.optString("providerReference"))
            .put("raw_response", envelope.toString())
            .put(
                "response_meta",
                JSONObject()
                    .put("provider", settings.provider)
                    .put("duplicate", duplicate)
                    .put("android_agent", true)
                    .put("test_only", settings.isMock)
            )

        client.acknowledge(
            credentials = credentials,
            transactionId = job.id,
            identity = identity,
            success = true,
            receipt = receipt
        )

        return FiscalAgentRunResult(
            processed = true,
            jobId = job.id,
            publicId = job.publicId,
            receiptNumber = receiptNumber,
            duplicate = duplicate
        )
    }

    companion object {
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
