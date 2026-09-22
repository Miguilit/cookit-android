package be.cookit.pos.android.data.fiscal

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Read-only fiscal resolution returned by CookitFiscal's shadow endpoint.
 *
 * A15.0F1 deliberately treats this as authoritative for TRAINING VAT selection while the immutable
 * local sale snapshot remains authoritative for order identity, quantities, prices, payment and
 * totals. Production fiscalization will persist the resolved treatment in the fiscal transaction
 * snapshot instead of resolving it after payment.
 */
data class FiscalShadowLineResolution(
    val orderItemId: Long?,
    val menuItemId: Long?,
    val name: String,
    val categoryId: Long?,
    val quantity: Double,
    val grossMinor: Long,
    val status: String,
    val country: String,
    val regime: String,
    val orderContext: String,
    val fiscalClass: String,
    val resultType: String?,
    val taxCategoryCode: String?,
    val rate: Double?,
    val reason: String?
)

data class FiscalShadowOrderResolution(
    val mode: String,
    val mutation: String,
    val status: String,
    val effectiveAt: String?,
    val profileEnabled: Boolean?,
    val country: String,
    val regime: String,
    val currency: String,
    val orderId: Long,
    val branchId: Long?,
    val orderContext: String,
    val commercialGrossMinor: Long,
    val resolvedGrossMinor: Long,
    val resolvedNetMinor: Long,
    val resolvedTaxMinor: Long,
    val lines: List<FiscalShadowLineResolution>,
    val warnings: List<String>
) {
    fun validateAgainst(snapshot: FiscalSaleSnapshot): List<FiscalShadowLineResolution> {
        require(mode.equals("shadow", ignoreCase = true)) { "CookitFiscal response is not SHADOW mode" }
        require(mutation.equals("none", ignoreCase = true)) { "CookitFiscal SHADOW response unexpectedly reports a mutation" }
        require(status.equals("ok", ignoreCase = true)) {
            val suffix = warnings.takeIf { it.isNotEmpty() }?.joinToString(" | ")?.let { ": $it" }.orEmpty()
            "CookitFiscal shadow resolution is $status$suffix"
        }
        require(orderId == snapshot.orderId) {
            "CookitFiscal order mismatch: backend #$orderId != local #${snapshot.orderId}"
        }
        require(country.equals("BE", ignoreCase = true)) {
            "Module2 TRAINING is Belgian; CookitFiscal resolved country $country"
        }
        require(currency.equals(snapshot.currency, ignoreCase = true)) {
            "CookitFiscal currency mismatch: $currency != ${snapshot.currency}"
        }
        val localContext = when (snapshot.orderType.trim().lowercase()) {
            "takeaway", "take_away", "pickup", "takeout" -> "pickup"
            "delivery" -> "delivery"
            else -> "dine_in"
        }
        require(orderContext.equals(localContext, ignoreCase = true)) {
            "CookitFiscal order context mismatch: $orderContext != $localContext"
        }
        require(resolvedNetMinor + resolvedTaxMinor == resolvedGrossMinor) {
            "CookitFiscal resolved totals are internally inconsistent"
        }
        require(commercialGrossMinor == snapshot.grossTotalMinor) {
            "CookitFiscal commercial gross differs from immutable snapshot ($commercialGrossMinor != ${snapshot.grossTotalMinor})"
        }
        require(resolvedGrossMinor == snapshot.grossTotalMinor) {
            "CookitFiscal resolved gross differs from immutable snapshot ($resolvedGrossMinor != ${snapshot.grossTotalMinor})"
        }
        require(lines.size == snapshot.lines.size) {
            "CookitFiscal line count differs from immutable snapshot (${lines.size} != ${snapshot.lines.size})"
        }

        val remaining = lines.toMutableList()
        val aligned = snapshot.lines.map { local ->
            val productId = local.productId.toLongOrNull()
                ?: error("Local fiscal product_id is not numeric: ${local.productId}")
            val index = remaining.indexOfFirst { remote ->
                remote.menuItemId == productId &&
                    remote.grossMinor == local.lineTotalMinor &&
                    abs(remote.quantity - local.quantity.toDouble()) < 0.000001
            }
            require(index >= 0) {
                "CookitFiscal could not match ${local.name} (#$productId, qty=${local.quantity}, gross=${local.lineTotalMinor})"
            }
            val remote = remaining.removeAt(index)
            require(remote.status.equals("resolved", ignoreCase = true)) {
                "CookitFiscal line ${remote.name} is ${remote.status}: ${remote.reason.orEmpty()}"
            }
            require(remote.country.equals(country, ignoreCase = true)) {
                "CookitFiscal line ${remote.name} country differs from profile"
            }
            require(remote.regime.equals(regime, ignoreCase = true)) {
                "CookitFiscal line ${remote.name} regime differs from profile"
            }
            require(remote.orderContext.equals(orderContext, ignoreCase = true)) {
                "CookitFiscal line ${remote.name} context differs from order context"
            }
            require(remote.resultType.equals("tax", ignoreCase = true)) {
                "Module2 TRAINING F1 currently requires TAX resolution; ${remote.name} resolved as ${remote.resultType}"
            }
            require(!remote.taxCategoryCode.isNullOrBlank()) {
                "CookitFiscal line ${remote.name} has no tax category code"
            }
            require(remote.rate != null) { "CookitFiscal line ${remote.name} has no resolved tax rate" }
            remote
        }
        require(remaining.isEmpty()) { "CookitFiscal returned unmatched fiscal lines" }
        return aligned
    }
}

object FiscalShadowResolutionParser {
    fun parse(envelope: JSONObject): FiscalShadowOrderResolution {
        require(envelope.optBoolean("success", true)) {
            envelope.optString("message").ifBlank { "CookitFiscal shadow endpoint returned success=false" }
        }
        val root = envelope.optJSONObject("data") ?: envelope
        val profile = root.optJSONObject("profile") ?: JSONObject()
        val order = root.optJSONObject("order") ?: JSONObject()
        val totals = root.optJSONObject("totals") ?: JSONObject()
        val rawLines = root.optJSONArray("lines") ?: JSONArray()
        val lines = buildList {
            for (index in 0 until rawLines.length()) {
                val line = rawLines.optJSONObject(index) ?: continue
                val resolution = line.optJSONObject("resolution") ?: JSONObject()
                add(
                    FiscalShadowLineResolution(
                        orderItemId = line.longOrNull("order_item_id"),
                        menuItemId = line.longOrNull("menu_item_id"),
                        name = line.optString("name").ifBlank { "Item" },
                        categoryId = line.longOrNull("category_id"),
                        quantity = line.doubleOrNull("quantity") ?: 0.0,
                        grossMinor = moneyToMinor(line.opt("gross")),
                        status = resolution.optString("status").ifBlank { "unresolved" },
                        country = resolution.optString("country"),
                        regime = resolution.optString("regime"),
                        orderContext = resolution.optString("order_context"),
                        fiscalClass = resolution.optString("fiscal_class").ifBlank { "UNCLASSIFIED" },
                        resultType = resolution.optString("result_type").takeIf { it.isNotBlank() },
                        taxCategoryCode = resolution.optString("tax_category_code").takeIf { it.isNotBlank() },
                        rate = resolution.doubleOrNull("rate"),
                        reason = resolution.optString("reason").takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        return FiscalShadowOrderResolution(
            mode = root.optString("mode").ifBlank { "unknown" },
            mutation = root.optString("mutation").ifBlank { "unknown" },
            status = root.optString("status").ifBlank { "unresolved" },
            effectiveAt = root.optString("effective_at").takeIf { it.isNotBlank() },
            profileEnabled = if (profile.has("enabled") && !profile.isNull("enabled")) profile.optBoolean("enabled") else null,
            country = profile.optString("country"),
            regime = profile.optString("regime"),
            currency = profile.optString("currency"),
            orderId = order.longOrNull("id") ?: error("CookitFiscal shadow response is missing order.id"),
            branchId = order.longOrNull("branch_id"),
            orderContext = order.optString("order_context"),
            commercialGrossMinor = moneyToMinor(totals.opt("commercial_gross")),
            resolvedGrossMinor = moneyToMinor(totals.opt("resolved_gross")),
            resolvedNetMinor = moneyToMinor(totals.opt("resolved_net")),
            resolvedTaxMinor = moneyToMinor(totals.opt("resolved_tax")),
            lines = lines,
            warnings = root.stringList("warnings")
        )
    }

    private fun moneyToMinor(raw: Any?): Long {
        val text = when (raw) {
            null, JSONObject.NULL -> "0"
            is Number -> raw.toString()
            else -> raw.toString().trim().replace(',', '.')
        }
        return runCatching {
            BigDecimal(text).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrElse { error("Invalid CookitFiscal money value: $text") }
    }
}

private fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}

private fun JSONObject.doubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
