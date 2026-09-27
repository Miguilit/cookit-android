package be.cookit.pos.android.data.fiscal


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
            "Cloud-prepared Module2 fiscal transport active; Android does not rebuild fiscal input."
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
