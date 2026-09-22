package be.cookit.pos.android.data.fiscal

/**
 * Orchestrates local POS -> provider submissions without mixing transport into checkout.
 * Checkbox remains fail-closed; the A14.4 Mock adapter is available only in debug builds.
 */
class FiscalFdmRuntime(
    private val client: FdmGraphqlClient,
    private val module2Client: Module2StatusClient,
    private val module2CredentialStore: Module2CredentialStore
) {
    private fun adapter(
        settings: FiscalFdmSettings
    ): FiscalProviderAdapter = when (settings.provider) {
        FiscalFdmSettings.PROVIDER_MOCK -> MockFiscalProviderAdapter()
        FiscalFdmSettings.PROVIDER_MODULE2 -> Module2FiscalProviderAdapter()
        else -> CheckboxFiscalProviderAdapter()
    }

    fun readiness(
        settings: FiscalFdmSettings
    ): FiscalProviderReadiness {
        val base = adapter(settings).readiness(settings)

        if (!settings.isModule2) {
            return base
        }

        val tokenReady =
            module2CredentialStore.loadBearerToken()
                ?.isNotBlank() == true

        return base.copy(
            networkConfigured =
                base.networkConfigured && tokenReady,
            reason = when {
                !base.networkConfigured ->
                    base.reason
                !tokenReady ->
                    "Module2 Bearer token is not configured"
                else ->
                    base.reason
            }
        )
    }

    suspend fun submitSale(
        settings: FiscalFdmSettings,
        event: FiscalOutboxEntity,
        preparedSale: PreparedFiscalSignSale? = null,
        headers: Map<String, String> = emptyMap()
    ): org.json.JSONObject {
        if (settings.isModule2) {
            val prepared = preparedSale
                ?: throw FiscalAgentIntegrityException(
                    "Module2 requires a cloud-prepared canonical signSale request"
                )

            if (!prepared.training) {
                throw FiscalAgentIntegrityException(
                    "A15.0F8.2 Module2 transport accepts TRAINING jobs only"
                )
            }

            val token =
                module2CredentialStore.loadBearerToken()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw FiscalAgentIntegrityException(
                        "Module2 Bearer token is not configured"
                    )

            return module2Client.executePreparedSignSale(
                settings = settings,
                bearerToken = token,
                requestCanonicalJson =
                    prepared.requestCanonicalJson
            )
        }

        return client.execute(
            settings = settings,
            operation =
                adapter(settings).buildSaleOperation(event),
            extraHeaders = headers
        )
    }
}
