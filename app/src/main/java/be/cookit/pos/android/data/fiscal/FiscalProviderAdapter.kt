package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.BuildConfig
import org.json.JSONObject

class FiscalProviderMappingUnavailable(message: String) : IllegalStateException(message)

data class FiscalProviderReadiness(
    val provider: String,
    val networkConfigured: Boolean,
    val mappingInstalled: Boolean,
    val mutationName: String,
    val reason: String? = null
) {
    val readyForFiscalization: Boolean get() = networkConfigured && mappingInstalled
}

/** Normalized provider health/status independent from the FDM manufacturer. */
data class FiscalProviderStatus(
    val provider: String = "",
    val transportConnected: Boolean = false,
    val statusAvailable: Boolean = false,
    val fdmId: String? = null,
    val firmwareVersion: String? = null,
    val fdmDateTime: String? = null,
    val bufferCapacityUsed: Double? = null,
    val initialized: Boolean? = null,
    val informations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val schemaVariant: String? = null,
    val message: String? = null
) {
    val healthy: Boolean get() = transportConnected && statusAvailable && initialized != false && errors.isEmpty()
}

/** Permanent seam between Cookit's canonical fiscal event and an FDM GraphQL schema. */
interface FiscalProviderAdapter {
    val providerId: String
    val saleMutationName: String

    fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness

    fun buildSaleOperation(event: FiscalOutboxEntity): FdmGraphqlOperation
}

/** Production Checkbox/Eutronix adapter boundary: intentionally fail-closed until certified mapping. */
class CheckboxFiscalProviderAdapter : FiscalProviderAdapter {
    override val providerId: String = FiscalFdmSettings.PROVIDER_CHECKBOX
    override val saleMutationName: String = "signSale"

    override fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness = FiscalProviderReadiness(
        provider = providerId,
        networkConfigured = settings.configured && settings.useTls,
        mappingInstalled = false,
        mutationName = saleMutationName,
        reason = "Exact certified Checkbox/Eutronix signSale input mapping pending device/schema validation"
    )

    override fun buildSaleOperation(event: FiscalOutboxEntity): FdmGraphqlOperation {
        throw FiscalProviderMappingUnavailable(
            "Checkbox/Eutronix signSale mapping is intentionally disabled until the certified POS GraphQL schema is validated."
        )
    }
}


/**
 * A15.0F8.2 Module2 boundary.
 *
 * Fiscal field mapping is no longer rebuilt on Android: Cookit Cloud supplies
 * the immutable canonical signSale request. The generic operation builder
 * therefore remains deliberately unavailable.
 */
class Module2FiscalProviderAdapter : FiscalProviderAdapter {
    override val providerId: String = FiscalFdmSettings.PROVIDER_MODULE2
    override val saleMutationName: String = "signSale"

    override fun readiness(
        settings: FiscalFdmSettings
    ): FiscalProviderReadiness = FiscalProviderReadiness(
        provider = providerId,
        networkConfigured = settings.configured && settings.useTls,
        mappingInstalled = true,
        mutationName = saleMutationName,
        reason = if (settings.configured && settings.useTls) {
            "A15.0F8.2 cloud-prepared Module2 signSale transport active (TRAINING only)"
        } else {
            "Module2 requires a configured HTTPS/mTLS endpoint"
        }
    )

    override fun buildSaleOperation(
        event: FiscalOutboxEntity
    ): FdmGraphqlOperation {
        throw FiscalProviderMappingUnavailable(
            "Module2 A15.0F8.2 requires the immutable cloud-prepared canonical request; Android must not rebuild SaleInput."
        )
    }
}

/**
 * A14.4 debug-only contract used by the separately distributed Cookit Mock FDM harness.
 *
 * This schema is Cookit-owned and deliberately NOT presented as an Eutronix/Checkbox schema. It lets
 * us exercise hashing, idempotency, retry and ambiguous-response behavior before the dev box arrives.
 */
class MockFiscalProviderAdapter : FiscalProviderAdapter {
    override val providerId: String = FiscalFdmSettings.PROVIDER_MOCK
    override val saleMutationName: String = "mockSignSale"

    override fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness {
        val enabled = BuildConfig.ENABLE_MOCK_FDM && settings.isMock
        val transportOkay = settings.configured && (settings.useTls || MockFdmDebugGuard.cleartextAllowed(settings))
        return FiscalProviderReadiness(
            provider = providerId,
            networkConfigured = transportOkay,
            mappingInstalled = enabled,
            mutationName = saleMutationName,
            reason = when {
                !BuildConfig.ENABLE_MOCK_FDM -> "Mock FDM disabled in release builds"
                !settings.isMock -> "Mock provider not selected"
                !transportOkay -> "Mock FDM host/transport not ready"
                else -> "A14.4 test-only mapping active; not a certified Checkbox mapping"
            }
        )
    }

    override fun buildSaleOperation(event: FiscalOutboxEntity): FdmGraphqlOperation {
        check(BuildConfig.ENABLE_MOCK_FDM) { "Mock FDM is disabled in this build" }

        val input = JSONObject()
            .put("localEventId", event.localEventId)
            .put("idempotencyKey", event.idempotencyKey)
            .put("runtimeId", event.runtimeId)
            .put("terminalId", event.terminalId)
            .put("restaurantId", event.restaurantId)
            .put("branchId", event.branchId)
            .put("orderId", event.orderId)
            .put("eventClass", event.sceEventClass)
            .put("eventType", event.sceEventType)
            .put("currency", event.currency)
            .put("grossTotalMinor", event.grossTotalMinor)
            .put("snapshotJson", event.snapshotJson)
            .put("snapshotHash", event.snapshotHash)

        return FdmGraphqlOperation(
            operationName = "CookitMockSignSale",
            query = """
                mutation CookitMockSignSale(${ '$' }input: MockSaleInput!) {
                  signSale(input: ${ '$' }input) {
                    success
                    receiptNumber
                    signature
                    verificationCode
                    providerReference
                    duplicate
                  }
                }
            """.trimIndent(),
            variables = JSONObject().put("input", input)
        )
    }
}
