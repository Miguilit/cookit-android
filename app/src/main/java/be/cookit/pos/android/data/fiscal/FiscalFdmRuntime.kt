package be.cookit.pos.android.data.fiscal

/**
 * Orchestrates local POS -> provider submissions without mixing transport into checkout.
 * Checkbox remains fail-closed; the A14.4 Mock adapter is available only in debug builds.
 */
class FiscalFdmRuntime(
    private val client: FdmGraphqlClient
) {
    private fun adapter(settings: FiscalFdmSettings): FiscalProviderAdapter = when (settings.provider) {
        FiscalFdmSettings.PROVIDER_MOCK -> MockFiscalProviderAdapter()
        FiscalFdmSettings.PROVIDER_MODULE2 -> Module2FiscalProviderAdapter()
        else -> CheckboxFiscalProviderAdapter()
    }

    fun readiness(settings: FiscalFdmSettings): FiscalProviderReadiness = adapter(settings).readiness(settings)

    suspend fun submitSale(
        settings: FiscalFdmSettings,
        event: FiscalOutboxEntity,
        headers: Map<String, String> = emptyMap()
    ) = client.execute(
        settings = settings,
        operation = adapter(settings).buildSaleOperation(event),
        extraHeaders = headers
    )
}
