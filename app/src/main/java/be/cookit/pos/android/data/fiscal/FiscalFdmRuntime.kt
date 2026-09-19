package be.cookit.pos.android.data.fiscal

/**
 * Orchestrates one local POS -> certified FDM submission without mixing transport into POS checkout.
 * The current Checkbox adapter fails closed before any network request until its exact schema is installed.
 */
class FiscalFdmRuntime(
    private val adapter: FiscalProviderAdapter,
    private val client: FdmGraphqlClient
) {
    fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness = adapter.readiness(settings)

    suspend fun submitSale(
        settings: FiscalFdmSettings,
        event: FiscalOutboxEntity,
        headers: Map<String, String> = emptyMap()
    ) = client.execute(
        settings = settings,
        operation = adapter.buildSaleOperation(event),
        extraHeaders = headers
    )
}
