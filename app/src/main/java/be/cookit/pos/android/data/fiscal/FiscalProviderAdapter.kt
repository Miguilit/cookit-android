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

/** Permanent seam between Cookit's canonical fiscal event and a certified FDM GraphQL schema. */
interface FiscalProviderAdapter {
    val providerId: String
    val saleMutationName: String

    fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness

    fun buildSaleOperation(event: FiscalOutboxEntity): FdmGraphqlOperation
}

/**
 * Checkbox/Eutronix adapter boundary.
 *
 * The Belgian FDM material identifies `signSale` as the mandatory mutation for NORMAL/N events, but
 * its exact input object is defined by the detailed POS schema. Until that exact certified schema and
 * the Checkbox test device are validated, Cookit MUST fail closed instead of inventing field names.
 */
class CheckboxFiscalProviderAdapter : FiscalProviderAdapter {
    override val providerId: String = FiscalFdmSettings.PROVIDER_CHECKBOX
    override val saleMutationName: String = "signSale"

    override fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness = FiscalProviderReadiness(
        provider = providerId,
        networkConfigured = settings.configured,
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
