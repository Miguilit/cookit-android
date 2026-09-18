package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.OrderType
import org.json.JSONArray
import org.json.JSONObject

data class DraftEntry(val productId: Long, val quantity: Int)

data class PersistedDraft(
    val entries: List<DraftEntry> = emptyList(),
    val orderType: OrderType = OrderType.DINE_IN,
    val tableId: Long? = null,
    val pendingOrderId: Long? = null
)

class DraftOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("cookit_pos_draft", Context.MODE_PRIVATE)

    fun load(): PersistedDraft {
        val raw = prefs.getString("draft", null) ?: return PersistedDraft()
        return runCatching {
            val json = JSONObject(raw)
            val entries = buildList {
                val array = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optLong("product_id", -1L)
                    val qty = item.optInt("quantity", 0)
                    if (id > 0 && qty > 0) add(DraftEntry(id, qty))
                }
            }
            val orderType = runCatching {
                OrderType.valueOf(json.optString("order_type", OrderType.DINE_IN.name))
            }.getOrDefault(OrderType.DINE_IN)
            PersistedDraft(
                entries = entries,
                orderType = orderType,
                tableId = json.optLong("table_id").takeIf { json.has("table_id") && !json.isNull("table_id") && it > 0 },
                pendingOrderId = json.optLong("pending_order_id").takeIf {
                    json.has("pending_order_id") && !json.isNull("pending_order_id") && it > 0
                }
            )
        }.getOrDefault(PersistedDraft())
    }

    fun save(draft: PersistedDraft) {
        val items = JSONArray()
        draft.entries.forEach {
            items.put(JSONObject().put("product_id", it.productId).put("quantity", it.quantity))
        }
        val json = JSONObject()
            .put("items", items)
            .put("order_type", draft.orderType.name)
            .put("table_id", draft.tableId ?: JSONObject.NULL)
            .put("pending_order_id", draft.pendingOrderId ?: JSONObject.NULL)
        prefs.edit().putString("draft", json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("draft").apply()
    }
}
