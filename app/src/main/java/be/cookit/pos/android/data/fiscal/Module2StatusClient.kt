package be.cookit.pos.android.data.fiscal

import android.content.Context
import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.CertificateFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Module2StatusResult(
    val endpoint: String = "",
    val connected: Boolean = false,
    val statusAvailable: Boolean = false,
    val httpStatus: Int? = null,
    val fdmId: String? = null,
    val fdmSwVersion: String? = null,
    val fdmDateTime: String? = null,
    val bufferCapacityUsed: Double? = null,
    val initialized: Boolean? = null,
    val informations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val latencyMs: Long? = null,
    val schemaVariant: String? = null,
    val message: String? = null
) {
    fun normalized(): FiscalProviderStatus = FiscalProviderStatus(
        provider = FiscalFdmSettings.PROVIDER_MODULE2,
        transportConnected = connected,
        statusAvailable = statusAvailable,
        fdmId = fdmId,
        firmwareVersion = fdmSwVersion,
        fdmDateTime = fdmDateTime,
        bufferCapacityUsed = bufferCapacityUsed,
        initialized = initialized,
        informations = informations,
        warnings = warnings,
        errors = errors,
        httpStatus = httpStatus,
        latencyMs = latencyMs,
        schemaVariant = schemaVariant,
        message = message
    )
}



data class Module2CookitTrainingLine(
    val productId: String,
    val productName: String,
    val departmentId: String,
    val departmentName: String,
    val quantity: Int,
    val unitPrice: Double,
    val vatRate: Double?,
    val vatLabel: String? = null
)

data class Module2TrainingSaleResult(
    val attempted: Boolean = false,
    val success: Boolean = false,
    val httpStatus: Int? = null,
    val latencyMs: Long? = null,
    val posFiscalTicketNo: Int? = null,
    val eventOperation: String? = null,
    val fdmId: String? = null,
    val fdmDateTime: String? = null,
    val eventLabel: String? = null,
    val eventCounter: Int? = null,
    val totalCounter: Int? = null,
    val digitalSignature: String? = null,
    val shortSignature: String? = null,
    val verificationUrl: String? = null,
    val bufferCapacityUsed: Double? = null,
    val vatCalc: List<String> = emptyList(),
    val fiscalResolution: List<String> = emptyList(),
    val financials: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val informations: List<String> = emptyList(),
    val footer: List<String> = emptyList(),
    val graphqlErrors: List<String> = emptyList(),
    val message: String? = null
)

private data class Module2HttpResponse(
    val status: Int,
    val body: String,
    val latencyMs: Long
)

private data class Module2StatusQueryCandidate(
    val variant: String,
    val query: String
)

/**
 * A15.0B Module2 read-only status client.
 *
 * GKS 2.0 defines a standard status query. Historic public specification revisions contained a
 * fmdSwVersion typo while current revisions use fdmSwVersion. We probe both forms and normalize the
 * response so the rest of Cookit never depends on a manufacturer/schema spelling detail.
 */
class Module2StatusClient(private val context: Context) {
    private companion object {
        val MODULE2_BELGIUM_ZONE: ZoneId = ZoneId.of("Europe/Brussels")
    }
    suspend fun status(
        settings: FiscalFdmSettings,
        bearerToken: String
    ): Module2StatusResult = withContext(Dispatchers.IO) {
        require(settings.isModule2) { "Module2 provider is not selected" }
        require(settings.useTls) { "Module2 requires HTTPS/mTLS" }
        require(settings.configured) { "Module2 endpoint is not configured" }
        val token = bearerToken.trim().removePrefix("Bearer ").trim()
        require(token.isNotBlank()) { "Module2 Bearer token is not configured" }

        val endpoint = settings.endpoint ?: error("Module2 endpoint unavailable")
        var totalLatency = 0L
        val failures = mutableListOf<String>()

        try {
            val sslContext = createModule2SslContext()
            for (candidate in statusCandidates()) {
                val response = post(
                    endpoint = endpoint,
                    bearerToken = token,
                    sslContext = sslContext,
                    operationName = "CookitModule2Status",
                    query = candidate.query
                )
                totalLatency += response.latencyMs

                if (response.status !in 200..299) {
                    return@withContext Module2StatusResult(
                        endpoint = endpoint,
                        connected = false,
                        statusAvailable = false,
                        httpStatus = response.status,
                        latencyMs = totalLatency,
                        message = "Module2 HTTP ${response.status}"
                    )
                }

                val envelope = runCatching { JSONObject(response.body) }.getOrNull()
                val payload = envelope?.optJSONObject("data")?.optJSONObject("status")
                if (payload != null) {
                    val device = payload.optJSONObject("device")
                    return@withContext Module2StatusResult(
                        endpoint = endpoint,
                        connected = true,
                        statusAvailable = true,
                        httpStatus = response.status,
                        fdmId = device?.optNonBlank("fdmId"),
                        fdmSwVersion = device?.optNonBlank("fdmSwVersion"),
                        fdmDateTime = device?.optNonBlank("fdmDateTime"),
                        bufferCapacityUsed = device?.optNullableDouble("bufferCapacityUsed"),
                        initialized = payload.optNullableBoolean("initialized"),
                        informations = payload.messageList("informations"),
                        warnings = payload.messageList("warnings"),
                        errors = payload.messageList("errors"),
                        latencyMs = totalLatency,
                        schemaVariant = candidate.variant,
                        message = "Module2 status OK (${candidate.variant})"
                    )
                }

                failures += "${candidate.variant}: ${envelope?.firstGraphqlError() ?: "data.status missing"}"
            }

            // Transport-only fallback. This deliberately performs no fiscal mutation.
            val handshake = post(
                endpoint = endpoint,
                bearerToken = token,
                sslContext = sslContext,
                operationName = "CookitModule2Handshake",
                query = "query CookitModule2Handshake { __typename }"
            )
            totalLatency += handshake.latencyMs
            val handshakeEnvelope = runCatching { JSONObject(handshake.body) }.getOrNull()
            val handshakeOkay = handshake.status in 200..299 &&
                handshakeEnvelope?.optJSONObject("data")?.optString("__typename")?.isNotBlank() == true

            Module2StatusResult(
                endpoint = endpoint,
                connected = handshakeOkay,
                statusAvailable = false,
                httpStatus = handshake.status,
                latencyMs = totalLatency,
                schemaVariant = "TRANSPORT_ONLY",
                message = if (handshakeOkay) {
                    "Module2 transport OK; status schema unresolved (${failures.joinToString(" | ")})"
                } else {
                    handshakeEnvelope?.firstGraphqlError()
                        ?: "Module2 GraphQL handshake failed (${failures.joinToString(" | ")})"
                }
            )
        } catch (error: Throwable) {
            Module2StatusResult(
                endpoint = endpoint,
                connected = false,
                statusAvailable = false,
                latencyMs = totalLatency.takeIf { it > 0 },
                message = error.message ?: error::class.java.simpleName
            )
        }
    }

    /**
     * A15.0C manual TRAINING mutation.
     *
     * This deliberately uses the sample identifiers published in the Module2 developer manual and
     * sets isTraining=true. It is never called by the automatic fiscal agent. The purpose is to
     * validate the complete signSale GraphQL path against the Module2 simulator before Cookit maps
     * real restaurant/order data into SaleInput.
     */
    suspend fun trainingSale(
        settings: FiscalFdmSettings,
        bearerToken: String
    ): Module2TrainingSaleResult = withContext(Dispatchers.IO) {
        require(settings.isModule2) { "Module2 provider is not selected" }
        require(settings.useTls) { "Module2 requires HTTPS/mTLS" }
        require(settings.configured) { "Module2 endpoint is not configured" }
        val token = bearerToken.trim().removePrefix("Bearer ").trim()
        require(token.isNotBlank()) { "Module2 Bearer token is not configured" }

        val endpoint = settings.endpoint ?: error("Module2 endpoint unavailable")
        val ticketNo = nextTrainingTicketNo()
        val now = OffsetDateTime.now(MODULE2_BELGIUM_ZONE).withNano(0)
        val deviceId = trainingDeviceId()
        val bookingPeriodId = trainingBookingPeriodId(now.toLocalDate().toString())

        val data = JSONObject()
            .put("language", "EN")
            .put("vatNo", "BE0000000097")
            .put("estNo", "2000000042")
            .put("posId", "CPOS0031234567")
            .put("posFiscalTicketNo", ticketNo)
            .put("posDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("posSwVersion", BuildConfig.VERSION_NAME)
            .put("deviceId", deviceId)
            .put("terminalId", "COOKIT-ANDROID-TRAINING")
            .put("bookingPeriodId", bookingPeriodId)
            .put("bookingDate", now.toLocalDate().toString())
            .put("ticketMedium", "PAPER")
            .put("employeeId", "84022899837")
            .put(
                "transaction",
                JSONObject()
                    .put(
                        "transactionLines",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("lineType", "SINGLE_PRODUCT")
                                    .put(
                                        "mainProduct",
                                        JSONObject()
                                            .put("productId", "10006")
                                            .put("productName", "Dry Martini")
                                            .put("departmentId", "10")
                                            .put("departmentName", "Aperitifs")
                                            .put("quantity", 2)
                                            .put("quantityType", "PIECE")
                                            .put("unitPrice", 12.0)
                                            .put(
                                                "vats",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("label", "A")
                                                        .put("price", 24.0)
                                                )
                                            )
                                    )
                                    .put("lineTotal", 24.0)
                            )
                            .put(
                                JSONObject()
                                    .put("lineType", "SINGLE_PRODUCT")
                                    .put(
                                        "mainProduct",
                                        JSONObject()
                                            .put("productId", "22001")
                                            .put("productName", "Burger of the Chef")
                                            .put("departmentId", "22")
                                            .put("departmentName", "Main Dishes")
                                            .put("quantity", 1)
                                            .put("quantityType", "PIECE")
                                            .put("unitPrice", 28.0)
                                            .put(
                                                "vats",
                                                JSONArray().put(
                                                    JSONObject()
                                                        .put("label", "B")
                                                        .put("price", 28.0)
                                                )
                                            )
                                    )
                                    .put("lineTotal", 28.0)
                            )
                    )
                    .put("transactionTotal", 52.0)
            )
            .put(
                "financials",
                JSONArray().put(
                    JSONObject()
                        .put("id", "1")
                        .put("name", "CASH")
                        .put("type", "CASH")
                        .put("inputMethod", "MANUAL")
                        .put("amount", 52.0)
                        .put("amountType", "PAYMENT")
                        .put("drawer", JSONObject().put("id", "1").put("name", "Drawer 1"))
                )
            )

        val query = """
            mutation CookitModule2TrainingSale(${'$'}data: SaleInput!, ${'$'}training: Boolean! = true) {
              signSale(data: ${'$'}data, isTraining: ${'$'}training) {
                posId
                posFiscalTicketNo
                posDateTime
                terminalId
                deviceId
                eventOperation
                fdmSwVersion
                digitalSignature
                shortSignature
                verificationUrl
                bufferCapacityUsed
                fdmRef {
                  fdmId
                  fdmDateTime
                  eventLabel
                  eventCounter
                  totalCounter
                }
                vatCalc {
                  label
                  rate
                  taxableAmount
                  vatAmount
                  totalAmount
                  outOfScope
                }
                warnings { message }
                informations { message }
                footer
              }
            }
        """.trimIndent()

        try {
            val sslContext = createModule2SslContext()
            val response = post(
                endpoint = endpoint,
                bearerToken = token,
                sslContext = sslContext,
                operationName = "CookitModule2TrainingSale",
                query = query,
                variables = JSONObject().put("data", data).put("training", true)
            )
            val envelope = runCatching { JSONObject(response.body) }.getOrNull()
            val graphqlErrors = envelope?.graphqlErrors().orEmpty()
            val sale = envelope?.optJSONObject("data")?.optJSONObject("signSale")

            if (response.status !in 200..299 || sale == null || graphqlErrors.isNotEmpty()) {
                return@withContext Module2TrainingSaleResult(
                    attempted = true,
                    success = false,
                    httpStatus = response.status,
                    latencyMs = response.latencyMs,
                    posFiscalTicketNo = ticketNo,
                    graphqlErrors = graphqlErrors,
                    message = graphqlErrors.firstOrNull()
                        ?: if (response.status !in 200..299) "Module2 HTTP ${response.status}" else "data.signSale missing"
                )
            }

            val fdmRef = sale.optJSONObject("fdmRef")
            Module2TrainingSaleResult(
                attempted = true,
                success = true,
                httpStatus = response.status,
                latencyMs = response.latencyMs,
                posFiscalTicketNo = sale.optInt("posFiscalTicketNo").takeIf { sale.has("posFiscalTicketNo") },
                eventOperation = sale.optNonBlank("eventOperation"),
                fdmId = fdmRef?.optNonBlank("fdmId"),
                fdmDateTime = fdmRef?.optNonBlank("fdmDateTime"),
                eventLabel = fdmRef?.optNonBlank("eventLabel"),
                eventCounter = fdmRef?.optNullableInt("eventCounter"),
                totalCounter = fdmRef?.optNullableInt("totalCounter"),
                digitalSignature = sale.optNonBlank("digitalSignature"),
                shortSignature = sale.optNonBlank("shortSignature"),
                verificationUrl = sale.optNonBlank("verificationUrl"),
                bufferCapacityUsed = sale.optNullableDouble("bufferCapacityUsed"),
                vatCalc = sale.vatCalcList(),
                warnings = sale.messageList("warnings"),
                informations = sale.messageList("informations"),
                footer = sale.stringList("footer"),
                message = "Module2 TRAINING signSale OK"
            )
        } catch (error: Throwable) {
            Module2TrainingSaleResult(
                attempted = true,
                success = false,
                posFiscalTicketNo = ticketNo,
                message = error.message ?: error::class.java.simpleName
            )
        }
    }

    /**
     * A15.0D maps the actual Cookit cart/order lines to GKS SaleInput while keeping the simulator
     * identity in TRAINING mode. Missing/unsupported VAT metadata is fail-closed: Cookit refuses
     * to send rather than guessing a fiscal rate.
     */
    suspend fun trainingSaleFromCookit(
        settings: FiscalFdmSettings,
        bearerToken: String,
        lines: List<Module2CookitTrainingLine>,
        paymentMethod: String,
        terminalId: String
    ): Module2TrainingSaleResult = withContext(Dispatchers.IO) {
        require(settings.isModule2) { "Module2 provider is not selected" }
        require(settings.useTls) { "Module2 requires HTTPS/mTLS" }
        require(settings.configured) { "Module2 endpoint is not configured" }
        require(lines.isNotEmpty()) { "Cookit cart is empty" }
        val token = bearerToken.trim().removePrefix("Bearer ").trim()
        require(token.isNotBlank()) { "Module2 Bearer token is not configured" }

        val endpoint = settings.endpoint ?: error("Module2 endpoint unavailable")
        val ticketNo = nextTrainingTicketNo()
        val now = OffsetDateTime.now(MODULE2_BELGIUM_ZONE).withNano(0)
        val deviceId = trainingDeviceId()
        val bookingPeriodId = trainingBookingPeriodId(now.toLocalDate().toString())
        val transactionLines = JSONArray()
        var transactionTotal = 0.0

        lines.forEach { line ->
            require(line.quantity > 0) { "Invalid quantity for ${line.productName}" }
            require(line.unitPrice >= 0.0) { "Invalid price for ${line.productName}" }
            val lineTotal = money2(line.unitPrice * line.quantity)
            val label = normalizeVatLabel(line.vatLabel, line.vatRate)
            transactionTotal = money2(transactionTotal + lineTotal)
            transactionLines.put(
                JSONObject()
                    .put("lineType", "SINGLE_PRODUCT")
                    .put(
                        "mainProduct",
                        JSONObject()
                            .put("productId", line.productId.take(600))
                            .put("productName", line.productName.take(600))
                            .put("departmentId", line.departmentId.take(600))
                            .put("departmentName", line.departmentName.take(600))
                            .put("quantity", line.quantity)
                            .put("quantityType", "PIECE")
                            .put("unitPrice", money2(line.unitPrice))
                            .put("vats", JSONArray().put(JSONObject().put("label", label).put("price", lineTotal)))
                    )
                    .put("lineTotal", lineTotal)
            )
        }

        val normalizedPayment = paymentMethod.trim().lowercase()
        val paymentType = when (normalizedPayment) {
            "cash" -> "CASH"
            "card" -> "CARD_UNKNOWN"
            else -> error("Unsupported Cookit payment method for Module2 TRAINING: $paymentMethod")
        }
        val paymentName = if (paymentType == "CASH") "CASH" else "CARD"
        val payment = JSONObject()
            .put("id", if (paymentType == "CASH") "cash" else "card")
            .put("name", paymentName)
            .put("type", paymentType)
            .put("inputMethod", "MANUAL")
            .put("amount", transactionTotal)
            .put("amountType", "PAYMENT")
        if (paymentType == "CASH") {
            payment.put("drawer", JSONObject().put("id", "1").put("name", "Drawer 1"))
        }

        val data = JSONObject()
            .put("language", "EN")
            // Simulator identity remains intentionally fixed until Cookit production provisioning is implemented.
            .put("vatNo", "BE0000000097")
            .put("estNo", "2000000042")
            .put("posId", "CPOS0031234567")
            .put("posFiscalTicketNo", ticketNo)
            .put("posDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("posSwVersion", BuildConfig.VERSION_NAME)
            .put("deviceId", deviceId)
            .put("terminalId", terminalId.ifBlank { "COOKIT-ANDROID-TRAINING" }.take(600))
            .put("bookingPeriodId", bookingPeriodId)
            .put("bookingDate", now.toLocalDate().toString())
            .put("ticketMedium", "PAPER")
            .put("employeeId", "84022899837")
            .put("transaction", JSONObject().put("transactionLines", transactionLines).put("transactionTotal", transactionTotal))
            .put("financials", JSONArray().put(payment))

        executeTrainingSale(endpoint, token, ticketNo, data, "CookitModule2CookitCartTrainingSale")
    }

    /**
     * A15.0F2 sends the immutable fiscal-outbox snapshot of a PAID Cookit order to the Module2
     * simulator with isTraining=true, but VAT treatment is supplied by CookitFiscal SHADOW.
     * The immutable local snapshot remains authoritative for identity, quantities, prices, payment
     * and totals; the backend resolver is authoritative for country/context/class/rate plus allocated adjustments/financials. Both sides
     * are cross-checked before any GraphQL mutation is attempted.
     */
    suspend fun trainingSaleFromFinalizedEvent(
        settings: FiscalFdmSettings,
        bearerToken: String,
        event: FiscalOutboxEntity,
        shadowResolution: FiscalShadowOrderResolution
    ): Module2TrainingSaleResult = withContext(Dispatchers.IO) {
        require(settings.isModule2) { "Module2 provider is not selected" }
        require(settings.useTls) { "Module2 requires HTTPS/mTLS" }
        require(settings.configured) { "Module2 endpoint is not configured" }
        require(event.activatedAtEpochMs != null) { "Cookit fiscal event is not activated by a completed payment" }

        val token = bearerToken.trim().removePrefix("Bearer ").trim()
        require(token.isNotBlank()) { "Module2 Bearer token is not configured" }
        val snapshot = FiscalSaleSnapshotParser.parse(event)
        require(snapshot.schema == "cookit.android.fiscal.sale.v2") {
            "This paid order uses ${snapshot.schema}; create and pay a new order with A15.0E+ to obtain fiscal snapshot v2"
        }
        require(snapshot.lines.isNotEmpty()) { "Finalized Cookit order has no fiscal lines" }
        val resolvedLines = shadowResolution.validateAgainst(snapshot)
        val endpoint = settings.endpoint ?: error("Module2 endpoint unavailable")
        val ticketNo = nextTrainingTicketNo()
        val now = OffsetDateTime.now(MODULE2_BELGIUM_ZONE).withNano(0)
        val module2PosDateTime = module2BelgiumDateTime(snapshot.posDateTime, now)
        val deviceId = trainingDeviceId()
        // Module2 validates Belgian civil time: +01:00 in winter, +02:00 in summer.
        // Keep the immutable snapshot instant, but serialize that same instant in Europe/Brussels.
        val bookingDate = module2PosDateTime.toLocalDate().toString()
        val bookingPeriodId = trainingBookingPeriodId(bookingDate)
        val transactionLines = JSONArray()

        snapshot.lines.forEachIndexed { index, line ->
            require(line.quantity > 0) { "Invalid quantity for ${line.name}" }
            require(line.unitPriceMinor >= 0L && line.lineTotalMinor >= 0L) { "Invalid amount for ${line.name}" }
            val resolved = resolvedLines[index]
            val label = normalizeVatLabel(null, resolved.rate)
            transactionLines.put(
                JSONObject()
                    .put("lineType", "SINGLE_PRODUCT")
                    .put(
                        "mainProduct",
                        JSONObject()
                            .put("productId", line.productId.take(600))
                            .put("productName", line.name.take(600))
                            .put("departmentId", (line.departmentId ?: "0").take(600))
                            .put("departmentName", (line.departmentName ?: "Cookit").take(600))
                            .put("quantity", line.quantity)
                            .put("quantityType", "PIECE")
                            .put("unitPrice", minorToMoney(line.unitPriceMinor))
                            .put(
                                "vats",
                                JSONArray().put(
                                    JSONObject()
                                        .put("label", label)
                                        .put("price", minorToMoney(line.lineTotalMinor))
                                )
                            )
                    )
                    .put("lineTotal", minorToMoney(line.lineTotalMinor))
            )
        }

        shadowResolution.adjustments.forEachIndexed { index, adjustment ->
            val rate = adjustment.rate ?: error("Missing VAT rate for ${adjustment.name}")
            val label = normalizeVatLabel(null, rate)
            transactionLines.put(
                JSONObject()
                    .put("lineType", "SINGLE_PRODUCT")
                    .put(
                        "mainProduct",
                        JSONObject()
                            .put("productId", "COOKIT-${adjustment.kind.uppercase()}-${index + 1}".take(600))
                            .put("productName", adjustment.name.take(600))
                            .put("departmentId", "DELIVERY")
                            .put("departmentName", "Delivery")
                            .put("quantity", 1)
                            .put("quantityType", "PIECE")
                            .put("unitPrice", minorToMoney(adjustment.grossMinor))
                            .put(
                                "vats",
                                JSONArray().put(
                                    JSONObject()
                                        .put("label", label)
                                        .put("price", minorToMoney(adjustment.grossMinor))
                                )
                            )
                    )
                    .put("lineTotal", minorToMoney(adjustment.grossMinor))
            )
        }

        val financials = JSONArray()
        shadowResolution.financials.forEach { financial ->
            val financialJson = JSONObject()
                .put("id", financial.id.take(600))
                .put("name", financial.name.take(600))
                .put("type", financial.type.uppercase())
                .put("inputMethod", financial.inputMethod.uppercase())
                .put("amount", minorToMoney(financial.amountMinor))
                .put("amountType", financial.amountType.uppercase())
            if (financial.type.equals("CASH", ignoreCase = true)) {
                financialJson.put("drawer", JSONObject().put("id", "1").put("name", "Drawer 1"))
            }
            financials.put(financialJson)
        }

        val data = JSONObject()
            .put("language", "EN")
            // Simulator-only fiscal identity. Cookit order/cashier/terminal data comes from snapshot v2.
            .put("vatNo", "BE0000000097")
            .put("estNo", "2000000042")
            .put("posId", "CPOS0031234567")
            .put("posFiscalTicketNo", ticketNo)
            .put("posDateTime", module2PosDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("posSwVersion", BuildConfig.VERSION_NAME)
            .put("deviceId", deviceId)
            .put("terminalId", snapshot.terminalId.ifBlank { "COOKIT-ANDROID-TRAINING" }.take(600))
            .put("bookingPeriodId", bookingPeriodId)
            .put("bookingDate", bookingDate)
            .put("ticketMedium", "PAPER")
            // Keep the vendor's simulator employee identifier until production employee mapping is provisioned.
            .put("employeeId", "84022899837")
            .put(
                "transaction",
                JSONObject()
                    .put("transactionLines", transactionLines)
                    .put("transactionTotal", minorToMoney(snapshot.grossTotalMinor))
            )
            .put("financials", financials)

        val result = executeTrainingSale(endpoint, token, ticketNo, data, "CookitModule2FinalizedOrderTrainingSale")
        result.copy(
            fiscalResolution = buildList {
                resolvedLines.forEach { resolved ->
                    add("${resolved.name}: ${resolved.fiscalClass} -> ${resolved.taxCategoryCode} @ ${resolved.rate}%")
                }
                shadowResolution.adjustments.forEach { adjustment ->
                    add("${adjustment.name}: ${adjustment.taxCategoryCode} @ ${adjustment.rate}% [${adjustment.strategy.orEmpty()}]")
                }
            },
            financials = shadowResolution.financials.map { financial ->
                "${financial.type.uppercase()} ${minorToMoney(financial.amountMinor)} EUR (${financial.source.orEmpty()})"
            }
        )
    }

    private fun module2BelgiumDateTime(raw: String?, fallback: OffsetDateTime): OffsetDateTime {
        val instant = raw
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { value ->
                runCatching {
                    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                }.recoverCatching {
                    Instant.parse(value)
                }.getOrNull()
            }
            ?: fallback.toInstant()

        return instant
            .atZone(MODULE2_BELGIUM_ZONE)
            .toOffsetDateTime()
            .withNano(0)
    }

    private fun minorToMoney(value: Long): Double = value.toDouble() / 100.0

    private suspend fun executeTrainingSale(
        endpoint: String,
        token: String,
        ticketNo: Int,
        data: JSONObject,
        operationName: String
    ): Module2TrainingSaleResult {
        val query = """
            mutation $operationName(${ '$' }data: SaleInput!, ${ '$' }training: Boolean! = true) {
              signSale(data: ${ '$' }data, isTraining: ${ '$' }training) {
                posId posFiscalTicketNo posDateTime terminalId deviceId eventOperation fdmSwVersion
                digitalSignature shortSignature verificationUrl bufferCapacityUsed
                fdmRef { fdmId fdmDateTime eventLabel eventCounter totalCounter }
                vatCalc { label rate taxableAmount vatAmount totalAmount outOfScope }
                warnings { message }
                informations { message }
                footer
              }
            }
        """.trimIndent()
        return try {
            val response = post(
                endpoint = endpoint,
                bearerToken = token,
                sslContext = createModule2SslContext(),
                operationName = operationName,
                query = query,
                variables = JSONObject().put("data", data).put("training", true)
            )
            val envelope = runCatching { JSONObject(response.body) }.getOrNull()
            val graphqlErrors = envelope?.graphqlErrors().orEmpty()
            val sale = envelope?.optJSONObject("data")?.optJSONObject("signSale")
            if (response.status !in 200..299 || sale == null || graphqlErrors.isNotEmpty()) {
                Module2TrainingSaleResult(
                    attempted = true, success = false, httpStatus = response.status, latencyMs = response.latencyMs,
                    posFiscalTicketNo = ticketNo, graphqlErrors = graphqlErrors,
                    message = graphqlErrors.firstOrNull()
                        ?: if (response.status !in 200..299) "Module2 HTTP ${response.status}" else "data.signSale missing"
                )
            } else {
                val fdmRef = sale.optJSONObject("fdmRef")
                Module2TrainingSaleResult(
                    attempted = true, success = true, httpStatus = response.status, latencyMs = response.latencyMs,
                    posFiscalTicketNo = sale.optInt("posFiscalTicketNo").takeIf { sale.has("posFiscalTicketNo") },
                    eventOperation = sale.optNonBlank("eventOperation"), fdmId = fdmRef?.optNonBlank("fdmId"),
                    fdmDateTime = fdmRef?.optNonBlank("fdmDateTime"), eventLabel = fdmRef?.optNonBlank("eventLabel"),
                    eventCounter = fdmRef?.optNullableInt("eventCounter"), totalCounter = fdmRef?.optNullableInt("totalCounter"),
                    digitalSignature = sale.optNonBlank("digitalSignature"), shortSignature = sale.optNonBlank("shortSignature"),
                    verificationUrl = sale.optNonBlank("verificationUrl"), bufferCapacityUsed = sale.optNullableDouble("bufferCapacityUsed"),
                    vatCalc = sale.vatCalcList(), warnings = sale.messageList("warnings"),
                    informations = sale.messageList("informations"), footer = sale.stringList("footer"),
                    message = "Module2 Cookit TRAINING signSale OK"
                )
            }
        } catch (error: Throwable) {
            Module2TrainingSaleResult(attempted = true, success = false, posFiscalTicketNo = ticketNo, message = error.message ?: error::class.java.simpleName)
        }
    }

    private fun normalizeVatLabel(explicit: String?, rate: Double?): String {
        val normalized = explicit?.trim()?.uppercase()?.takeIf { it in setOf("A", "B", "C", "D", "X") }
        if (normalized != null) return normalized
        val rawRate = rate ?: error("VAT metadata missing; Cookit refuses to guess a fiscal VAT label")
        val r = if (rawRate > 0.0 && rawRate <= 1.0) rawRate * 100.0 else rawRate
        return when {
            kotlin.math.abs(r - 21.0) < 0.001 -> "A"
            kotlin.math.abs(r - 12.0) < 0.001 -> "B"
            kotlin.math.abs(r - 6.0) < 0.001 -> "C"
            kotlin.math.abs(r) < 0.001 -> "D"
            else -> error("Unsupported VAT rate $rawRate; expected 21%, 12%, 6% or 0%")
        }
    }

    private fun money2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private fun trainingPreferences() =
        context.getSharedPreferences("cookit_module2_training", Context.MODE_PRIVATE)

    private fun nextTrainingTicketNo(): Int {
        val prefs = trainingPreferences()
        val current = prefs.getInt("next_ticket_no", 1000).coerceIn(1, 999_999_999)
        val next = if (current >= 999_999_999) 1 else current + 1
        prefs.edit().putInt("next_ticket_no", next).apply()
        return current
    }

    private fun trainingDeviceId(): String {
        val prefs = trainingPreferences()
        val existing = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        return UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }

    private fun trainingBookingPeriodId(bookingDate: String): String {
        val prefs = trainingPreferences()
        val dateKey = prefs.getString("booking_date", null)
        val existing = prefs.getString("booking_period_id", null)?.takeIf { it.isNotBlank() }
        if (dateKey == bookingDate && existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit()
            .putString("booking_date", bookingDate)
            .putString("booking_period_id", created)
            .apply()
        return created
    }

    private fun statusCandidates(): List<Module2StatusQueryCandidate> = listOf(
        // First mirror the Module2 developer manual exactly: status without arguments.
        Module2StatusQueryCandidate(
            variant = "MODULE2_MANUAL",
            query = """
                query CookitModule2Status {
                  status {
                    device {
                      fdmId
                      fdmSwVersion
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        ),
        // Keep a language-qualified form for GKS schema revisions that expose the argument.
        Module2StatusQueryCandidate(
            variant = "GKS_2_LANGUAGE_EN",
            query = """
                query CookitModule2Status {
                  status(language: EN) {
                    device {
                      fdmId
                      fdmSwVersion
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        ),
        // Historic public revisions contained fmdSwVersion; alias it to our normalized name.
        Module2StatusQueryCandidate(
            variant = "GKS_2_LEGACY_FMD_SW",
            query = """
                query CookitModule2Status {
                  status {
                    device {
                      fdmId
                      fdmSwVersion: fmdSwVersion
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        ),
        Module2StatusQueryCandidate(
            variant = "GKS_2_MINIMAL",
            query = """
                query CookitModule2Status {
                  status {
                    device {
                      fdmId
                      fdmDateTime
                      bufferCapacityUsed
                    }
                    initialized
                    informations { message }
                    warnings { message }
                    errors { message }
                  }
                }
            """.trimIndent()
        )
    )

    private fun post(
        endpoint: String,
        bearerToken: String,
        sslContext: SSLContext,
        operationName: String,
        query: String,
        variables: JSONObject = JSONObject()
    ): Module2HttpResponse {
        val started = System.nanoTime()
        var connection: HttpsURLConnection? = null
        try {
            val conn = (URI.create(endpoint).toURL().openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = module2HostnameVerifier(endpoint)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                setRequestProperty("User-Agent", "CookitPOS-Android-Module2-A15.0C")
            }
            connection = conn

            val body = JSONObject()
                .put("operationName", operationName)
                .put("query", query)
                .put("variables", variables)

            conn.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(body.toString()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

            return Module2HttpResponse(
                status = status,
                body = text,
                latencyMs = (System.nanoTime() - started) / 1_000_000L
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Module2's distributed server identity certificate currently uses a generic CN ("Server")
     * without a DNS/IP Subject Alternative Name. Android's default HTTPS verifier therefore
     * rejects fdm.module2.be (and would also reject a physical FDM reached by LAN IP) even after
     * the certificate chain has been successfully validated against the Module2 CA.
     *
     * Keep certificate-chain validation strict via createModule2SslContext(), but scope the
     * hostname exception to the exact host configured in the selected Module2 endpoint. This is
     * deliberately NOT a global trust-all verifier. If Module2 later serves a hostname-valid
     * certificate, the platform verifier wins first and this compatibility path is not used.
     */
    private fun module2HostnameVerifier(endpoint: String): HostnameVerifier {
        val configuredHost = runCatching { URI.create(endpoint).host }.getOrNull()
        val platformVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return HostnameVerifier { hostname, session ->
            platformVerifier.verify(hostname, session) ||
                (configuredHost != null && hostname.equals(configuredHost, ignoreCase = true))
        }
    }

    private fun JSONObject.firstGraphqlError(): String? {
        val errors = optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        return errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() }
            ?: errors.optString(0).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.graphqlErrors(): List<String> {
        val array = optJSONArray("errors") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                val message = item?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: array.optString(index).takeIf { it.isNotBlank() }
                if (message != null) add(message)
            }
        }
    }

    private fun JSONObject.vatCalcList(): List<String> {
        val array = optJSONArray("vatCalc") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val label = item.optString("label").ifBlank { "?" }
                val rate = item.optDouble("rate", Double.NaN)
                val total = item.optDouble("totalAmount", Double.NaN)
                val vat = item.optDouble("vatAmount", Double.NaN)
                add(
                    buildString {
                        append(label)
                        if (rate.isFinite()) append(" ${rate}%")
                        if (total.isFinite()) append(" total=${total}")
                        if (vat.isFinite()) append(" vat=${vat}")
                    }
                )
            }
        }
    }

    private fun JSONObject.stringList(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optNonBlank(name: String): String? =
        optString(name).takeIf { has(name) && !isNull(name) && it.isNotBlank() }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrNull()
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getDouble(name) }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getBoolean(name) }.getOrNull()
    }

    private fun JSONObject.messageList(name: String): List<String> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                item.optString("message").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun createModule2SslContext(): SSLContext {
        // Module2 distributes the client identity with an empty-password PKCS#12 bundle.
        // Android providers are inconsistent when decrypting empty-password PKCS#12 files
        // (some devices throw IllegalArgumentException: password empty).  Load the same
        // published client certificate + unencrypted PKCS#8 key directly instead.
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val clientCertificate = context.resources.openRawResource(R.raw.module2_client_cert).use { stream ->
            certificateFactory.generateCertificate(stream)
        }
        val caCertificate = context.resources.openRawResource(R.raw.module2_ca).use { stream ->
            certificateFactory.generateCertificate(stream)
        }
        val privateKey = context.resources.openRawResource(R.raw.module2_client_key).use { stream ->
            val pem = stream.bufferedReader(StandardCharsets.US_ASCII).use { it.readText() }
            val base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
            val encoded = Base64.decode(base64, Base64.DEFAULT)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))
        }

        // The password below protects only this in-memory KeyStore entry. It is not a
        // Module2 credential and is never transmitted or persisted.
        val inMemoryKeyPassword = "cookit-module2-mtls".toCharArray()
        val clientStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(
                "module2-client",
                privateKey,
                inMemoryKeyPassword,
                arrayOf(clientCertificate, caCertificate)
            )
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientStore, inMemoryKeyPassword)
        }.keyManagers

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("module2-root-ca", caCertificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }.trustManagers

        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
    }
}
