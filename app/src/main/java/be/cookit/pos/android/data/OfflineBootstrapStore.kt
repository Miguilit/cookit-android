package be.cookit.pos.android.data

import android.content.Context
import be.cookit.pos.android.domain.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal last-known-good bootstrap used only to survive a cold start without Cookit Cloud.
 * It never stores payment/FDM secrets and never marks the device online.
 */
data class OfflineBootstrapSnapshot(
    val platform: PlatformSnapshot,
    val catalog: CatalogSnapshot,
    val orders: List<PosOrder>,
    val tables: List<DiningTable>,
    val savedAtEpochMs: Long
)

class OfflineBootstrapStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "cookit_pos_offline_bootstrap",
        Context.MODE_PRIVATE
    )

    fun save(
        platform: PlatformSnapshot,
        catalog: CatalogSnapshot,
        orders: List<PosOrder>,
        tables: List<DiningTable>
    ) {
        val root = JSONObject()
            .put("schema", "cookit.android.offline.bootstrap.v1")
            .put("saved_at", System.currentTimeMillis())
            .put("user", encodeUser(platform.user))
            .put("policy", encodePolicy(platform.policy))
            .put("categories", JSONArray().apply { catalog.categories.forEach { put(encodeCategory(it)) } })
            .put("products", JSONArray().apply { catalog.products.forEach { put(encodeProduct(it)) } })
            .put("orders", JSONArray().apply { orders.forEach { put(encodeOrder(it)) } })
            .put("tables", JSONArray().apply { tables.forEach { put(encodeTable(it)) } })

        prefs.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
    }

    fun load(): OfflineBootstrapSnapshot? = runCatching {
        val raw = prefs.getString(KEY_SNAPSHOT, null)?.takeIf { it.isNotBlank() } ?: return null
        val root = JSONObject(raw)
        if (root.optString("schema") != "cookit.android.offline.bootstrap.v1") return null

        val user = decodeUser(root.getJSONObject("user"))
        val policy = decodePolicy(root.getJSONObject("policy"))
        val categories = decodeArray(root.optJSONArray("categories")) { decodeCategory(it) }
        val products = decodeArray(root.optJSONArray("products")) { decodeProduct(it) }
        val orders = decodeArray(root.optJSONArray("orders")) { decodeOrder(it) }
        val tables = decodeArray(root.optJSONArray("tables")) { decodeTable(it) }

        OfflineBootstrapSnapshot(
            platform = PlatformSnapshot(user, policy),
            catalog = CatalogSnapshot(categories, products),
            orders = orders,
            tables = tables,
            savedAtEpochMs = root.optLong("saved_at", 0L)
        )
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    private fun encodeUser(user: UserSession) = JSONObject()
        .put("id", user.id)
        .put("name", user.name)
        .put("role", user.role.name)
        .put("restaurant", user.restaurant)
        .put("branch", user.branch)
        .putNullable("restaurant_logo_url", user.restaurantLogoUrl)
        .putNullable("restaurant_id", user.restaurantId)
        .putNullable("branch_id", user.branchId)

    private fun decodeUser(json: JSONObject) = UserSession(
        id = json.optLong("id", 0L),
        name = json.optString("name", "Utilisateur"),
        role = runCatching { PosRole.valueOf(json.optString("role")) }.getOrDefault(PosRole.CASHIER),
        restaurant = json.optString("restaurant", "Cookit Restaurant"),
        branch = json.optString("branch", "Branche"),
        restaurantLogoUrl = json.optNullableString("restaurant_logo_url"),
        restaurantId = json.optNullableLong("restaurant_id"),
        branchId = json.optNullableLong("branch_id")
    )

    private fun encodePolicy(policy: NativePolicy) = JSONObject()
        .put("profile", policy.profile.name)
        .put("cash_session_required", policy.cashSessionRequired)
        .put("can_manage_settings", policy.canManageSettings)
        .put("can_manage_printers", policy.canManagePrinters)
        .put("can_use_pos", policy.canUsePos)
        .put("can_view_kds", policy.canViewKds)
        .put("can_view_delivery", policy.canViewDelivery)
        .put("can_approve_cash_register", policy.canApproveCashRegister)
        .put("can_view_cash_register_reports", policy.canViewCashRegisterReports)

    private fun decodePolicy(json: JSONObject) = NativePolicy(
        profile = runCatching { PosRole.valueOf(json.optString("profile")) }.getOrDefault(PosRole.CASHIER),
        cashSessionRequired = json.optBoolean("cash_session_required", false),
        canManageSettings = json.optBoolean("can_manage_settings", false),
        canManagePrinters = json.optBoolean("can_manage_printers", false),
        canUsePos = json.optBoolean("can_use_pos", false),
        canViewKds = json.optBoolean("can_view_kds", false),
        canViewDelivery = json.optBoolean("can_view_delivery", false),
        canApproveCashRegister = json.optBoolean("can_approve_cash_register", false),
        canViewCashRegisterReports = json.optBoolean("can_view_cash_register_reports", false)
    )

    private fun encodeCategory(category: Category) = JSONObject()
        .put("id", category.id)
        .put("name", category.name)
        .put("emoji", category.emoji)

    private fun decodeCategory(json: JSONObject) = Category(
        id = json.getLong("id"),
        name = json.optString("name"),
        emoji = json.optString("emoji")
    )

    private fun encodeProduct(product: Product): JSONObject {
        val json = JSONObject()
            .put("id", product.id)
            .put("category_id", product.categoryId)
            .put("name", product.name)
            .put("description", product.description)
            .put("price", product.price)
            .put("emoji", product.emoji)
            .put("available", product.available)
            .putNullable("image_url", product.imageUrl)
            .putNullable("vat_rate", product.vatRate)
            .putNullable("vat_label", product.vatLabel)
            .put("has_modifiers", product.hasModifiers)
            .put(
                "commercial_type",
                product.commercialType
            )
            .putNullable(
                "composition_type",
                product.compositionType
            )
            .put(
                "requires_configuration",
                product.requiresConfiguration
            )

        product.composition?.let {
            json.put(
                "composition",
                encodeChoiceMenuComposition(it)
            )
        } ?: json.put(
            "composition",
            JSONObject.NULL
        )

        return json
    }

    private fun decodeProduct(json: JSONObject) = Product(
        id = json.getLong("id"),
        categoryId = json.getLong("category_id"),
        name = json.optString("name"),
        description = json.optString("description"),
        price = json.optDouble("price", 0.0),
        emoji = json.optString("emoji"),
        available = json.optBoolean("available", true),
        imageUrl = json.optNullableString("image_url"),
        vatRate = if (!json.has("vat_rate") || json.isNull("vat_rate")) {
            null
        } else {
            json.optDouble("vat_rate")
        },
        vatLabel = json.optNullableString("vat_label"),
        hasModifiers = json.optBoolean("has_modifiers", false),
        commercialType =
            json.optString(
                "commercial_type",
                "STANDARD"
            ),
        compositionType =
            json.optNullableString(
                "composition_type"
            ),
        requiresConfiguration =
            json.optBoolean(
                "requires_configuration",
                false
            ),
        composition =
            decodeChoiceMenuComposition(
                json.optJSONObject("composition")
            )
    )

    private fun encodeChoiceMenuComposition(
        composition: ChoiceMenuComposition
    ): JSONObject = JSONObject()
        .put("id", composition.id)
        .put("type", composition.type)
        .put("is_active", composition.isActive)
        .put(
            "groups",
            JSONArray().apply {
                composition.groups
                    .sortedWith(
                        compareBy<ChoiceMenuGroup> {
                            it.sortOrder
                        }.thenBy {
                            it.id
                        }
                    )
                    .forEach { group ->
                        put(
                            JSONObject()
                                .put("id", group.id)
                                .put("name", group.name)
                                .put(
                                    "min_select",
                                    group.minSelect
                                )
                                .put(
                                    "max_select",
                                    group.maxSelect
                                )
                                .put(
                                    "sort_order",
                                    group.sortOrder
                                )
                                .put(
                                    "options",
                                    JSONArray().apply {
                                        group.options
                                            .sortedWith(
                                                compareBy<ChoiceMenuOption> {
                                                    it.sortOrder
                                                }.thenBy {
                                                    it.id
                                                }
                                            )
                                            .forEach { option ->
                                                put(
                                                    JSONObject()
                                                        .put(
                                                            "id",
                                                            option.id
                                                        )
                                                        .putNullable(
                                                            "menu_item_id",
                                                            option.menuItemId
                                                        )
                                                        .put(
                                                            "name",
                                                            option.name
                                                        )
                                                        .put(
                                                            "quantity",
                                                            option.quantity
                                                        )
                                                        .put(
                                                            "price_delta",
                                                            option.priceDelta
                                                        )
                                                        .put(
                                                            "is_available",
                                                            option.isAvailable
                                                        )
                                                        .put(
                                                            "in_stock",
                                                            option.inStock
                                                        )
                                                        .putNullable(
                                                            "item_photo_url",
                                                            option.imageUrl
                                                        )
                                                        .put(
                                                            "sort_order",
                                                            option.sortOrder
                                                        )
                                                )
                                            }
                                    }
                                )
                        )
                    }
            }
        )

    private fun decodeChoiceMenuComposition(
        json: JSONObject?
    ): ChoiceMenuComposition? {
        val obj = json ?: return null
        val id = obj.optLong("id", -1L)
        val type =
            obj.optString("type", "")
                .trim()

        if (id <= 0L || type.isBlank()) {
            return null
        }

        val groupsJson =
            obj.optJSONArray("groups")
                ?: JSONArray()

        val groups = buildList {
            for (
                groupIndex in
                0 until groupsJson.length()
            ) {
                val groupJson =
                    groupsJson.optJSONObject(
                        groupIndex
                    ) ?: continue

                val groupId =
                    groupJson.optLong(
                        "id",
                        -1L
                    )

                if (groupId <= 0L) {
                    continue
                }

                val optionsJson =
                    groupJson.optJSONArray(
                        "options"
                    ) ?: JSONArray()

                val options = buildList {
                    for (
                        optionIndex in
                        0 until optionsJson.length()
                    ) {
                        val optionJson =
                            optionsJson.optJSONObject(
                                optionIndex
                            ) ?: continue

                        val optionId =
                            optionJson.optLong(
                                "id",
                                -1L
                            )

                        if (optionId <= 0L) {
                            continue
                        }

                        val menuItemId =
                            optionJson.optLong(
                                "menu_item_id",
                                -1L
                            ).takeIf {
                                it > 0L
                            }

                        add(
                            ChoiceMenuOption(
                                id = optionId,
                                menuItemId = menuItemId,
                                name =
                                    optionJson.optString(
                                        "name",
                                        "Option $optionId"
                                    ),
                                quantity =
                                    optionJson.optDouble(
                                        "quantity",
                                        1.0
                                    ),
                                priceDelta =
                                    optionJson.optDouble(
                                        "price_delta",
                                        0.0
                                    ),
                                isAvailable =
                                    optionJson.optBoolean(
                                        "is_available",
                                        true
                                    ),
                                inStock =
                                    optionJson.optBoolean(
                                        "in_stock",
                                        true
                                    ),
                                imageUrl =
                                    optionJson.optNullableString(
                                        "item_photo_url"
                                    ),
                                sortOrder =
                                    optionJson.optInt(
                                        "sort_order",
                                        0
                                    )
                            )
                        )
                    }
                }.sortedWith(
                    compareBy<ChoiceMenuOption> {
                        it.sortOrder
                    }.thenBy {
                        it.id
                    }
                )

                add(
                    ChoiceMenuGroup(
                        id = groupId,
                        name =
                            groupJson.optString(
                                "name",
                                "Groupe $groupId"
                            ),
                        minSelect =
                            groupJson.optInt(
                                "min_select",
                                0
                            ),
                        maxSelect =
                            groupJson.optInt(
                                "max_select",
                                1
                            ),
                        sortOrder =
                            groupJson.optInt(
                                "sort_order",
                                0
                            ),
                        options = options
                    )
                )
            }
        }.sortedWith(
            compareBy<ChoiceMenuGroup> {
                it.sortOrder
            }.thenBy {
                it.id
            }
        )

        return ChoiceMenuComposition(
            id = id,
            type = type,
            isActive =
                obj.optBoolean(
                    "is_active",
                    true
                ),
            groups = groups
        )
    }

    private fun encodeOrder(order: PosOrder) = JSONObject()
        .put("id", order.id)
        .put("code", order.code)
        .put("channel", order.channel)
        .put("type", order.type.name)
        .put("status", order.status)
        .put("total", order.total)
        .put("customer", order.customer)
        .putNullable("table", order.table)
        .put("minutes_ago", order.minutesAgo)
        .put("unread", false)
        .put("remote_status", order.remoteStatus)
        .put("settlement_status", order.settlementStatus)
        .putNullable("created_at", order.createdAtEpochMs)

    private fun decodeOrder(json: JSONObject) = PosOrder(
        id = json.getLong("id"),
        code = json.optString("code"),
        channel = json.optString("channel"),
        type = runCatching { OrderType.valueOf(json.optString("type")) }.getOrDefault(OrderType.DINE_IN),
        status = json.optString("status"),
        total = json.optDouble("total", 0.0),
        customer = json.optString("customer"),
        table = json.optNullableString("table"),
        minutesAgo = json.optInt("minutes_ago", 0),
        unread = false,
        remoteStatus = json.optString("remote_status", "placed"),
        settlementStatus = json.optString("settlement_status", "unknown"),
        createdAtEpochMs = json.optNullableLong("created_at")
    )

    private fun encodeTable(table: DiningTable) = JSONObject()
        .put("id", table.id)
        .put("label", table.label)
        .put("available", table.available)

    private fun decodeTable(json: JSONObject) = DiningTable(
        id = json.getLong("id"),
        label = json.optString("label"),
        available = json.optBoolean("available", true)
    )

    private fun <T> decodeArray(array: JSONArray?, decoder: (JSONObject) -> T): List<T> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            runCatching { decoder(obj) }.getOrNull()?.let(::add)
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = apply {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private companion object {
        const val KEY_SNAPSHOT = "last_known_good"
    }
}
