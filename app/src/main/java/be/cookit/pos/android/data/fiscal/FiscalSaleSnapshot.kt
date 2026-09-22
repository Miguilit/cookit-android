package be.cookit.pos.android.data.fiscal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable local representation of a paid Cookit order.
 *
 * A15.0E consumes this snapshot for manual Module2 TRAINING submission. The parser always verifies
 * the canonical SHA-256 before exposing any fiscal fields so a mutated local payload cannot be sent.
 */
data class FiscalSaleSnapshotLine(
    val productId: String,
    val name: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    val departmentId: String?,
    val departmentName: String?,
    val vatRate: Double?,
    val vatLabel: String?
)

data class FiscalSaleSnapshot(
    val schema: String,
    val localEventId: String,
    val orderId: Long,
    val orderType: String,
    val terminalId: String,
    val cashierId: Long?,
    val cashierName: String?,
    val posDateTime: String,
    val bookingDate: String,
    val paymentMethod: String,
    val paymentAmountMinor: Long,
    val currency: String,
    val grossTotalMinor: Long,
    val lines: List<FiscalSaleSnapshotLine>
)

object FiscalSaleSnapshotParser {
    fun parse(entity: FiscalOutboxEntity): FiscalSaleSnapshot {
        val calculatedHash = FiscalCanonicalJson.sha256Hex(entity.snapshotJson)
        require(calculatedHash.equals(entity.snapshotHash, ignoreCase = true)) {
            "Fiscal snapshot hash mismatch for ${entity.localEventId}"
        }

        val root = JSONObject(entity.snapshotJson)
        val payment = root.optJSONObject("payment") ?: JSONObject()
        val totals = root.optJSONObject("totals") ?: JSONObject()
        val cashier = root.optJSONObject("cashier")
        val rawLines = root.optJSONArray("lines") ?: JSONArray()
        val lines = buildList {
            for (index in 0 until rawLines.length()) {
                val line = rawLines.optJSONObject(index) ?: continue
                add(
                    FiscalSaleSnapshotLine(
                        productId = line.opt("product_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                            ?: error("Fiscal snapshot line is missing product_id"),
                        name = line.optString("name").takeIf { it.isNotBlank() }
                            ?: "Article ${line.opt("product_id")}",
                        quantity = line.optInt("quantity", 0),
                        unitPriceMinor = line.optNullableLong("unit_price_minor") ?: 0L,
                        lineTotalMinor = line.optNullableLong("line_total_minor") ?: 0L,
                        departmentId = line.opt("department_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                        departmentName = line.optString("department_name").takeIf { it.isNotBlank() },
                        vatRate = line.optNullableDouble("vat_rate"),
                        vatLabel = line.optString("vat_label").takeIf { it.isNotBlank() }
                    )
                )
            }
        }

        return FiscalSaleSnapshot(
            schema = root.optString("schema").ifBlank { "unknown" },
            localEventId = root.optString("local_event_id").ifBlank { entity.localEventId },
            orderId = root.optNullableLong("order_id") ?: entity.orderId,
            orderType = root.optString("order_type").ifBlank { entity.orderType },
            terminalId = root.optString("terminal_id").ifBlank { entity.terminalId },
            cashierId = cashier?.optNullableLong("id"),
            cashierName = cashier?.optString("name")?.takeIf { it.isNotBlank() },
            posDateTime = root.optString("pos_date_time"),
            bookingDate = root.optString("booking_date"),
            paymentMethod = payment.optString("method").ifBlank { entity.paymentMethod },
            paymentAmountMinor = payment.optNullableLong("amount_minor") ?: entity.grossTotalMinor,
            currency = payment.optString("currency").ifBlank { entity.currency },
            grossTotalMinor = totals.optNullableLong("gross_minor") ?: entity.grossTotalMinor,
            lines = lines
        )
    }
}

private fun JSONObject.optNullableLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }
}
