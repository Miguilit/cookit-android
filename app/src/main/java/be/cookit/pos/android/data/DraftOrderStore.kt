package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.OrderType
import org.json.JSONArray
import org.json.JSONObject

data class DraftModifier(
    val id: Long,
    val groupId: Long,
    val name: String,
    val price: Double
)

data class DraftChoiceSelectionGroup(
    val groupId: Long,
    val optionIds: List<Long>
)

data class DraftEntry(
    val productId: Long,
    val quantity: Int,
    val modifiers: List<DraftModifier> = emptyList(),
    val compositionSelection: List<DraftChoiceSelectionGroup> = emptyList()
)

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
                    if (id <= 0 || qty <= 0) continue

                    val modifiers = buildList {
                        val modifierArray = item.optJSONArray("modifiers") ?: JSONArray()
                        for (index in 0 until modifierArray.length()) {
                            val modifier = modifierArray.optJSONObject(index) ?: continue
                            val modifierId = modifier.optLong("id", -1L)
                            if (modifierId <= 0) continue
                            add(
                                DraftModifier(
                                    id = modifierId,
                                    groupId = modifier.optLong("group_id", 0L).coerceAtLeast(0L),
                                    name = modifier.optString("name", "").trim(),
                                    price = modifier.optDouble("price", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                                )
                            )
                        }
                    }

                    val compositionSelection = buildList {
                        val selectionArray =
                            item.optJSONArray("composition_selection")
                                ?: JSONArray()

                        for (
                            selectionIndex in
                            0 until selectionArray.length()
                        ) {
                            val selection =
                                selectionArray.optJSONObject(
                                    selectionIndex
                                ) ?: continue

                            val groupId =
                                selection.optLong(
                                    "group_id",
                                    -1L
                                )

                            if (groupId <= 0L) continue

                            val optionIdsArray =
                                selection.optJSONArray(
                                    "option_ids"
                                ) ?: JSONArray()

                            val optionIds = buildList {
                                for (
                                    optionIndex in
                                    0 until optionIdsArray.length()
                                ) {
                                    val optionId =
                                        optionIdsArray.optLong(
                                            optionIndex,
                                            -1L
                                        )

                                    if (optionId > 0L) {
                                        add(optionId)
                                    }
                                }
                            }
                                .distinct()
                                .sorted()

                            add(
                                DraftChoiceSelectionGroup(
                                    groupId = groupId,
                                    optionIds = optionIds
                                )
                            )
                        }
                    }
                        .distinctBy { it.groupId }
                        .sortedBy { it.groupId }

                    add(
                        DraftEntry(
                            productId = id,
                            quantity = qty,
                            modifiers = modifiers,
                            compositionSelection =
                                compositionSelection
                        )
                    )
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
        draft.entries.forEach { entry ->
            val modifiers = JSONArray()
            entry.modifiers.forEach { modifier ->
                modifiers.put(
                    JSONObject()
                        .put("id", modifier.id)
                        .put("group_id", modifier.groupId)
                        .put("name", modifier.name)
                        .put("price", modifier.price)
                )
            }
            val compositionSelection = JSONArray()

            entry.compositionSelection
                .sortedBy { it.groupId }
                .forEach { selectedGroup ->
                    compositionSelection.put(
                        JSONObject()
                            .put(
                                "group_id",
                                selectedGroup.groupId
                            )
                            .put(
                                "option_ids",
                                JSONArray().apply {
                                    selectedGroup.optionIds
                                        .distinct()
                                        .sorted()
                                        .forEach { optionId ->
                                            put(optionId)
                                        }
                                }
                            )
                    )
                }

            items.put(
                JSONObject()
                    .put("product_id", entry.productId)
                    .put("quantity", entry.quantity)
                    .put("modifiers", modifiers)
                    .put(
                        "composition_selection",
                        compositionSelection
                    )
            )
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
