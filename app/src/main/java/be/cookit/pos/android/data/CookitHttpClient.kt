package be.cookit.pos.android.data

import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class CookitApiException(
    val statusCode: Int,
    message: String,
    val responseBody: String = ""
) : Exception(message)

data class CatalogSnapshot(
    val categories: List<Category>,
    val products: List<Product>
)

data class PlatformSnapshot(
    val user: UserSession,
    val policy: NativePolicy
)

class CookitHttpClient {
    var languageCode: String = "fr"
    private val baseUrl = BuildConfig.COOKIT_API_BASE_URL.trimEnd('/') + "/"

    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)

        val json = request("auth/login", method = "POST", body = body)
        findStringDeep(json, setOf("token", "access_token", "plain_text_token", "plainTextToken"))
            ?: throw CookitApiException(200, "Le serveur n'a pas renvoyé de token d'accès.")
    }

    suspend fun platform(token: String, fallbackEmail: String): PlatformSnapshot = withContext(Dispatchers.IO) {
        val json = request("platform/config", token = token)

        val restaurantObj = findObjectDeep(json, setOf("restaurant"))
        val branchObj = findObjectDeep(json, setOf("branch"))
        val firstBranch = findArrayDeep(json, setOf("branches"))?.firstObject()
        val userObj = findObjectDeep(json, setOf("user"))

        val restaurantId = restaurantObj?.longAny("id", "restaurant_id")
            ?: json.longAny("restaurant_id")

        val restaurantName = restaurantObj?.optText("name", "restaurant_name")
            ?: json.optText("restaurant_name")
            ?: "Cookit Restaurant"

        val restaurantLogoUrl = normalizeMediaUrl(
            restaurantObj?.optText("logo_url", "logoUrl", "logo")
                ?: json.optText("restaurant_logo_url")
        )

        val branchId = branchObj?.longAny("id", "branch_id")
            ?: firstBranch?.longAny("id", "branch_id")
            ?: json.longAny("branch_id")

        val branchName = branchObj?.optText("name", "branch_name")
            ?: firstBranch?.optText("name", "branch_name")
            ?: json.optText("branch_name")
            ?: "Branche"

        val userName = userObj?.optText("name", "full_name")
            ?: fallbackEmail.substringBefore('@').ifBlank { "Utilisateur" }

        val roleText = userObj?.optText("role", "profile")
            ?: firstStringFromArray(userObj?.optJSONArray("roles"))
            ?: findStringDeep(json, setOf("role", "profile"))
            ?: "cashier"

        val role = mapRole(roleText)

        PlatformSnapshot(
            user = UserSession(
                id = userObj?.optLong("id", 0L) ?: 0L,
                name = userName,
                role = role,
                restaurant = restaurantName,
                branch = branchName,
                restaurantLogoUrl = restaurantLogoUrl,
                restaurantId = restaurantId,
                branchId = branchId
            ),
            policy = defaultPolicy(role)
        )
    }

    suspend fun catalog(token: String): CatalogSnapshot = withContext(Dispatchers.IO) {
        val categoriesJson = request("pos/categories", token = token)
        val itemsJson = request("pos/items", token = token)

        val categoryArray = findArrayDeep(categoriesJson, setOf("categories", "data")) ?: JSONArray()
        val itemArray = findArrayDeep(itemsJson, setOf("items", "menu_items", "data")) ?: JSONArray()

        val categories = buildList {
            for (i in 0 until categoryArray.length()) {
                val obj = categoryArray.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "category_id") ?: continue
                val name = obj.optText("name", "category_name", "title") ?: "Catégorie $id"
                add(Category(id, name, emojiForCategory(name)))
            }
        }.distinctBy { it.id }

        val products = buildList {
            for (i in 0 until itemArray.length()) {
                val obj = itemArray.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "item_id", "menu_item_id") ?: continue
                val categoryId = obj.longAny("category_id", "item_category_id", "menu_item_category_id")
                    ?: categories.firstOrNull()?.id
                    ?: 1L
                val name = obj.optText("name", "item_name", "title") ?: "Article $id"
                val description = obj.optText("description", "short_description") ?: ""
                val price = obj.doubleAny("price", "selling_price", "base_price", "amount") ?: 0.0
                val available = obj.boolAny("available", "is_available", "status") ?: true
                val imageUrl = normalizeMediaUrl(extractMediaCandidate(obj))
                val vatRate = extractVatRate(obj)
                val vatLabel = extractVatLabel(obj)
                add(Product(id, categoryId, name, description, price, emojiForProduct(name), available, imageUrl, vatRate, vatLabel))
            }
        }.distinctBy { it.id }

        val normalizedCategories = if (categories.isNotEmpty()) {
            listOf(Category(0, "Tous", "✨")) + categories
        } else {
            emptyList()
        }

        CatalogSnapshot(normalizedCategories, products)
    }

    suspend fun orders(token: String, branchId: Long? = null): List<PosOrder> = withContext(Dispatchers.IO) {
        val query = buildString {
            append("pos/orders?limit=100")
            if (branchId != null && branchId > 0) append("&branch_id=$branchId")
        }
        val json = request(query, token = token)
        val array = findArrayDeep(json, setOf("orders", "data")) ?: JSONArray()

        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "order_id") ?: continue
                val codeRaw = obj.optText("formatted_order_number", "order_number", "order_no", "code", "uuid") ?: id.toString()
                val channel = channelLabel(obj.optText("placed_via", "channel", "source") ?: "POS")
                val type = mapOrderType(obj.optText("order_type", "type") ?: "dine_in")

                // Cookit has two independent axes. A12.1 prefers the explicit API aliases
                // and remains backward-compatible with older RestApi payloads.
                val operationalStatus = (
                    obj.optText("operational_status", "order_status") ?: "placed"
                ).lowercase(Locale.ROOT)
                val settlementStatus = (
                    obj.optText("settlement_status", "status") ?: "unknown"
                ).lowercase(Locale.ROOT)

                val cartSummary = obj.optJSONObject("cart")?.optJSONObject("summary")
                val financials = obj.optJSONObject("financials")
                val total = obj.doubleAny("grand_total", "total", "total_amount")
                    ?: cartSummary?.doubleAny("grand_total", "total")
                    ?: financials?.doubleAny("total", "grand_total")
                    ?: obj.doubleAny("amount")
                    ?: 0.0
                val customerObj = obj.optJSONObject("customer")
                val tableObj = obj.optJSONObject("table")
                val customer = customerObj?.optText("name", "full_name")
                    ?: obj.optText("customer_name")
                    ?: tableObj?.optText("table_name", "table_code", "name")
                    ?: "Client"
                val table = tableObj?.optText("table_name", "table_code", "name")
                    ?: obj.optText("table_name", "table_code")

                val createdRaw = obj.optText("created_at", "date_time", "order_date", "createdAt")
                val createdEpoch = parseServerEpochMs(createdRaw)

                add(
                    PosOrder(
                        id = id,
                        code = if (codeRaw.startsWith("#")) codeRaw else "#$codeRaw",
                        channel = channel,
                        type = type,
                        status = humanStatus(operationalStatus),
                        remoteStatus = operationalStatus,
                        settlementStatus = settlementStatus,
                        total = total,
                        customer = customer,
                        table = table,
                        minutesAgo = minutesSince(createdEpoch),
                        createdAtEpochMs = createdEpoch,
                        unread = false
                    )
                )
            }
        }
    }

    suspend fun orderDraft(token: String, orderId: Long): RemoteOrderDraft = withContext(Dispatchers.IO) {
        val json = request("pos/orders/$orderId", token = token)

        // Current RestApi returns the detailed order at the root and also includes an
        // `order` raw-row object. A12 incorrectly descended into that raw object, which
        // discarded root-level items/financials and reopened orders as an empty basket.
        val obj = when {
            json.has("id") || json.has("items") || json.has("financials") -> json
            json.optJSONObject("data") != null -> json.optJSONObject("data")!!
            json.optJSONObject("order") != null -> json.optJSONObject("order")!!
            else -> json
        }

        val rawItems = obj.optJSONArray("items")
            ?: obj.optJSONObject("cart")?.optJSONArray("items")
            ?: JSONArray()
        val lines = buildList {
            for (i in 0 until rawItems.length()) {
                val item = rawItems.optJSONObject(i) ?: continue
                val nestedMenu = item.optJSONObject("menu_item") ?: item.optJSONObject("menuItem")
                val menuItemId = item.longAny("menu_item_id", "item_id")
                    ?: nestedMenu?.longAny("id", "menu_item_id")
                    ?: continue
                val qty = (item.longAny("quantity", "qty") ?: 1L).toInt().coerceAtLeast(1)
                val amount = item.doubleAny("amount", "total")
                val directPrice = item.doubleAny("price", "unit_price")
                    ?: nestedMenu?.doubleAny("price", "selling_price", "final_price", "base_price")
                val price = directPrice?.takeIf { it > 0 }
                    ?: amount?.takeIf { it > 0 }?.div(qty)
                    ?: 0.0
                val name = item.optText("menu_item_name", "item_name", "name")
                    ?: nestedMenu?.optText("item_name", "name")
                val categoryId = item.longAny("category_id", "item_category_id", "menu_item_category_id")
                    ?: nestedMenu?.longAny("category_id", "item_category_id", "menu_item_category_id")
                val vatRate = extractVatRate(item) ?: nestedMenu?.let(::extractVatRate)
                val vatLabel = extractVatLabel(item) ?: nestedMenu?.let(::extractVatLabel)
                add(RemoteOrderLine(menuItemId, qty, price, name, categoryId, vatRate, vatLabel))
            }
        }

        val financials = obj.optJSONObject("financials")
        val cartSummary = obj.optJSONObject("cart")?.optJSONObject("summary")
        val effectiveTotal = obj.doubleAny("grand_total", "total", "total_amount")
            ?: financials?.doubleAny("total", "grand_total")
            ?: cartSummary?.doubleAny("grand_total", "total")
            ?: lines.sumOf { it.price * it.quantity }

        val type = mapOrderType(obj.optText("order_type", "type") ?: "dine_in")
        RemoteOrderDraft(
            orderId = obj.longAny("id", "order_id") ?: orderId,
            type = type,
            tableId = obj.longAny("table_id", "dining_table_id"),
            total = effectiveTotal,
            settlementStatus = (
                obj.optText("settlement_status", "status") ?: "unknown"
            ).lowercase(Locale.ROOT),
            operationalStatus = (
                obj.optText("operational_status", "order_status") ?: "placed"
            ).lowercase(Locale.ROOT),
            lines = lines
        )
    }

    suspend fun dashboard(token: String): be.cookit.pos.android.domain.DashboardSnapshot = withContext(Dispatchers.IO) {
        val json = request("pos/dashboard", token = token)
        val data = json.optJSONObject("data") ?: json

        val salesJson = data.optJSONArray("sales_data") ?: JSONArray()
        val sales = buildList {
            for (i in 0 until salesJson.length()) {
                val point = salesJson.optJSONObject(i) ?: continue
                add(
                    be.cookit.pos.android.domain.DashboardSalesPoint(
                        date = point.optText("date") ?: "",
                        total = point.optDouble("total", 0.0)
                    )
                )
            }
        }

        val ordersJson = data.optJSONArray("today_orders_list") ?: JSONArray()
        val orders = buildList {
            for (i in 0 until ordersJson.length()) {
                val order = ordersJson.optJSONObject(i) ?: continue
                val id = order.longAny("id", "order_id") ?: continue
                add(
                    be.cookit.pos.android.domain.DashboardOrderSummary(
                        id = id,
                        code = (order.optText("code") ?: "").ifBlank { "#$id" },
                        status = (order.optText("status") ?: "").ifBlank { "placed" },
                        settlementStatus = (order.optText("settlement_status") ?: "").ifBlank { "unknown" },
                        type = mapOrderType((order.optText("order_type") ?: "").ifBlank { "dine_in" }),
                        customer = (order.optText("customer") ?: "").ifBlank { "Client" },
                        table = order.optText("table")?.takeIf { it.isNotBlank() },
                        total = order.optDouble("total", 0.0),
                        createdAtEpochMs = parseServerEpochMs(order.optText("created_at"))
                    )
                )
            }
        }

        be.cookit.pos.android.domain.DashboardSnapshot(
            todayOrders = data.optInt("today_orders", 0),
            todayOrdersChange = data.optDouble("today_orders_change", 0.0),
            todayRevenue = data.optDouble("today_revenue", 0.0),
            todayRevenueChange = data.optDouble("today_revenue_change", 0.0),
            todayCustomers = data.optInt("today_customers", 0),
            todayCustomersChange = data.optDouble("today_customers_change", 0.0),
            averageDailyRevenue = data.optDouble("average_daily_revenue", 0.0),
            averageDailyRevenueChange = data.optDouble("average_daily_revenue_change", 0.0),
            monthlyRevenue = data.optDouble("monthly_revenue", 0.0),
            monthlyRevenueChange = data.optDouble("monthly_revenue_change", 0.0),
            salesData = sales,
            todayOrdersList = orders
        )
    }

    suspend fun deliveryBootstrap(token: String): Pair<List<be.cookit.pos.android.domain.DeliveryExecutive>, be.cookit.pos.android.domain.DeliverySettings?> = withContext(Dispatchers.IO) {
        val json = request("pos/delivery-bootstrap", token = token)
        val data = json.optJSONObject("data") ?: json
        val executivesJson = data.optJSONArray("executives") ?: JSONArray()
        val executives = buildList {
            for (i in 0 until executivesJson.length()) {
                val obj = executivesJson.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "delivery_executive_id") ?: continue
                add(
                    be.cookit.pos.android.domain.DeliveryExecutive(
                        id = id,
                        name = (obj.optText("name") ?: "").ifBlank { "#$id" },
                        status = (obj.optText("status") ?: "").ifBlank { "available" }
                    )
                )
            }
        }
        val settingsJson = data.optJSONObject("settings")
        val settings = settingsJson?.let {
            be.cookit.pos.android.domain.DeliverySettings(
                enabled = it.optBoolean("is_enabled", false),
                feeType = it.optString("fee_type", "fixed"),
                fixedFee = it.optDouble("fixed_fee", 0.0),
                maxRadius = if (it.has("max_radius") && !it.isNull("max_radius")) it.optDouble("max_radius") else null,
                unit = it.optString("unit", "km")
            )
        }
        executives to settings
    }

    suspend fun deliveryOrders(token: String): List<be.cookit.pos.android.domain.DeliveryOrderSummary> = withContext(Dispatchers.IO) {
        val json = request("pos/delivery-orders?limit=100", token = token)
        val array = findArrayDeep(json, setOf("data", "orders")) ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "order_id") ?: continue
                val customerObj = obj.optJSONObject("customer")
                add(
                    be.cookit.pos.android.domain.DeliveryOrderSummary(
                        id = id,
                        code = (obj.optText("formatted_order_number", "order_number") ?: "").let { raw ->
                            if (raw.isBlank()) "#$id" else raw
                        },
                        status = (obj.optText("status") ?: "").ifBlank { "placed" },
                        total = obj.optDouble("total", 0.0),
                        deliveryFee = obj.optDouble("delivery_fee", 0.0),
                        deliveryAddress = obj.optText("delivery_address")?.takeIf { it.isNotBlank() },
                        deliveryExecutiveId = obj.longAny("delivery_executive_id"),
                        customer = customerObj?.optText("name")?.takeIf { it.isNotBlank() } ?: "Client",
                        createdAtEpochMs = parseServerEpochMs(obj.optText("created_at"))
                    )
                )
            }
        }
    }

    suspend fun kots(token: String): List<KotTicket> = withContext(Dispatchers.IO) {
        val json = request("pos/kots?limit=100", token = token)
        val array = findArrayDeep(json, setOf("data", "kots")) ?: JSONArray()

        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "kot_id") ?: continue
                val orderId = obj.longAny("order_id") ?: continue
                val codeRaw = obj.optText("formatted_order_number", "order_number") ?: orderId.toString()
                val itemsJson = obj.optJSONArray("items") ?: JSONArray()
                val items = buildList {
                    for (j in 0 until itemsJson.length()) {
                        val item = itemsJson.optJSONObject(j) ?: continue
                        add(
                            KotItem(
                                id = item.longAny("id", "kot_item_id") ?: j.toLong(),
                                menuItemId = item.longAny("menu_item_id"),
                                name = item.optText("name", "item_name") ?: "Article",
                                quantity = (item.longAny("quantity", "qty") ?: 1L).toInt().coerceAtLeast(1),
                                status = item.optText("status") ?: "pending",
                                note = item.optText("note")
                            )
                        )
                    }
                }
                add(
                    KotTicket(
                        id = id,
                        orderId = orderId,
                        orderCode = if (codeRaw.startsWith("#")) codeRaw else "#$codeRaw",
                        type = mapOrderType(obj.optText("order_type") ?: "dine_in"),
                        tableName = obj.optText("table_name"),
                        kitchenPlace = obj.optText("kitchen_place"),
                        status = (obj.optText("status") ?: "pending_confirmation").lowercase(Locale.ROOT),
                        items = items,
                        createdAtEpochMs = parseServerEpochMs(obj.optText("created_at"))
                    )
                )
            }
        }
    }

    suspend fun updateKotStatus(token: String, kotId: Long, status: String) = withContext(Dispatchers.IO) {
        request(
            "pos/kots/$kotId/status",
            method = "PUT",
            token = token,
            body = JSONObject().put("status", status)
        )
        Unit
    }


    suspend fun tables(token: String): List<DiningTable> = withContext(Dispatchers.IO) {
        val json = request("pos/tables", token = token)
        val array = findArrayDeep(json, setOf("tables", "data")) ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "table_id") ?: continue
                val label = obj.optText("table_name", "name", "table_code", "code") ?: "Table $id"
                val available = when {
                    obj.boolAny("is_available", "available") != null -> obj.boolAny("is_available", "available") ?: true
                    obj.has("is_running") -> !obj.optBoolean("is_running", false)
                    else -> true
                }
                add(DiningTable(id, label, available))
            }
        }
    }

    suspend fun createOrder(
        token: String,
        type: OrderType,
        lines: List<CartLine>,
        tableId: Long? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        deliveryAddress: String? = null,
        deliveryFee: Double = 0.0,
        deliveryExecutiveId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) throw CookitApiException(422, "Panier vide")
        val items = JSONArray()
        lines.forEach { line ->
            items.put(
                JSONObject()
                    .put("id", line.product.id)
                    .put("menu_item_id", line.product.id)
                    .put("quantity", line.quantity)
                    .put("price", line.product.price)
                    .put("amount", line.total)
            )
        }
        val body = JSONObject()
            .put("order_type", when (type) {
                OrderType.DINE_IN -> "dine_in"
                OrderType.TAKEAWAY -> "pickup"
                OrderType.DELIVERY -> "delivery"
            })
            .put("placed_via", "pos")
            .put("items", items)
        if (type == OrderType.DINE_IN && tableId != null) body.put("table_id", tableId)
        if (type == OrderType.DELIVERY) {
            val customer = JSONObject()
            customerName?.trim()?.takeIf { it.isNotEmpty() }?.let { customer.put("name", it) }
            customerPhone?.trim()?.takeIf { it.isNotEmpty() }?.let { customer.put("phone", it) }
            if (customer.length() > 0) body.put("customer", customer)
            deliveryAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("delivery_address", it) }
            body.put("delivery_fee", deliveryFee.coerceAtLeast(0.0))
            deliveryExecutiveId?.let { body.put("delivery_executive_id", it) }
        }

        val json = request("pos/orders", method = "POST", token = token, body = body)
        val orderObj = findObjectDeep(json, setOf("order"))
        orderObj?.longAny("id", "order_id")
            ?: json.longAny("id", "order_id")
            ?: throw CookitApiException(200, "Commande créée mais identifiant introuvable.")
    }

    suspend fun createKot(token: String, orderId: Long) = withContext(Dispatchers.IO) {
        request("pos/orders/$orderId/kot", method = "POST", token = token, body = JSONObject())
        Unit
    }

    suspend fun ensureKot(token: String, orderId: Long) = withContext(Dispatchers.IO) {
        val existing = request("pos/orders/$orderId/kots", token = token)
        val kots = findArrayDeep(existing, setOf("kots", "data"))
        if (kots == null || kots.length() == 0) {
            request("pos/orders/$orderId/kot", method = "POST", token = token, body = JSONObject())
        }
        Unit
    }

    suspend fun payOrder(
        token: String,
        orderId: Long,
        amount: Double,
        method: PosPaymentMethod
    ) = withContext(Dispatchers.IO) {
        // Deployed Cookit RestApi versions accept the legacy flat contract while
        // newer documentation exposes a payments[] contract. Prefer the deployed
        // flat contract and fall back only on validation errors.
        val flatBody = JSONObject()
            .put("amount", amount)
            .put("method", method.apiValue)

        try {
            request(
                "pos/orders/$orderId/pay",
                method = "POST",
                token = token,
                body = flatBody
            )
        } catch (e: CookitApiException) {
            if (e.statusCode !in setOf(400, 422)) throw e

            val payments = JSONArray().put(
                JSONObject()
                    .put("amount", amount)
                    .put("method", method.apiValue)
            )
            request(
                "pos/orders/$orderId/pay",
                method = "POST",
                token = token,
                body = JSONObject().put("payments", payments)
            )
        }
        Unit
    }

    suspend fun updateOrderStatus(token: String, orderId: Long, status: String) = withContext(Dispatchers.IO) {
        request(
            "pos/orders/$orderId/status",
            method = "POST",
            token = token,
            body = JSONObject().put("status", status)
        )
        Unit
    }

    suspend fun billingContext(token: String, orderId: Long): BillingContext = withContext(Dispatchers.IO) {
        parseBillingContext(request("pos/orders/$orderId/billing-context", token = token))
    }

    suspend fun splitEqual(token: String, orderId: Long, parts: Int): BillingContext = withContext(Dispatchers.IO) {
        parseBillingContext(
            request(
                "pos/orders/$orderId/split/equal",
                method = "POST",
                token = token,
                body = JSONObject().put("parts", parts)
            )
        )
    }

    suspend fun splitCustom(token: String, orderId: Long, amounts: List<Double>): BillingContext = withContext(Dispatchers.IO) {
        val array = JSONArray()
        amounts.forEach { array.put(it) }
        parseBillingContext(
            request(
                "pos/orders/$orderId/split/custom",
                method = "POST",
                token = token,
                body = JSONObject().put("amounts", array)
            )
        )
    }

    suspend fun splitItems(token: String, orderId: Long, items: Map<Long, Int>): BillingContext = withContext(Dispatchers.IO) {
        val array = JSONArray()
        items.forEach { (orderItemId, quantity) ->
            array.put(
                JSONObject()
                    .put("order_item_id", orderItemId)
                    .put("quantity", quantity)
            )
        }
        parseBillingContext(
            request(
                "pos/orders/$orderId/split/items",
                method = "POST",
                token = token,
                body = JSONObject().put("items", array)
            )
        )
    }

    suspend fun cancelSplit(token: String, orderId: Long): BillingContext = withContext(Dispatchers.IO) {
        parseBillingContext(
            request(
                "pos/orders/$orderId/split",
                method = "DELETE",
                token = token
            )
        )
    }

    suspend fun paySplitBill(
        token: String,
        billId: Long,
        amount: Double,
        method: PosPaymentMethod
    ): BillingContext = withContext(Dispatchers.IO) {
        parseBillingContext(
            request(
                "pos/split-bills/$billId/pay",
                method = "POST",
                token = token,
                body = JSONObject()
                    .put("amount", amount)
                    .put("method", method.apiValue)
            )
        )
    }

    suspend fun mergeTables(token: String, orderId: Long, tableIds: List<Long>): BillingContext = withContext(Dispatchers.IO) {
        val ids = JSONArray()
        tableIds.forEach { ids.put(it) }
        parseBillingContext(
            request(
                "pos/orders/$orderId/merge-tables",
                method = "POST",
                token = token,
                body = JSONObject().put("table_ids", ids)
            )
        )
    }

    suspend fun unmergeTables(token: String, orderId: Long, tableIds: List<Long> = emptyList()): BillingContext = withContext(Dispatchers.IO) {
        val ids = JSONArray()
        tableIds.forEach { ids.put(it) }
        parseBillingContext(
            request(
                "pos/orders/$orderId/unmerge-tables",
                method = "POST",
                token = token,
                body = JSONObject().put("table_ids", ids)
            )
        )
    }

    private fun parseBillingContext(json: JSONObject): BillingContext {
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        val lines = buildList {
            for (i in 0 until itemsJson.length()) {
                val row = itemsJson.optJSONObject(i) ?: continue
                val id = row.longAny("order_item_id", "id") ?: continue
                add(
                    BillingLine(
                        orderItemId = id,
                        menuItemId = row.longAny("menu_item_id"),
                        name = row.optText("name") ?: "Article #$id",
                        quantity = (row.longAny("quantity", "qty") ?: 1L).toInt().coerceAtLeast(1),
                        unitPrice = row.doubleAny("unit_price", "price") ?: 0.0,
                        amount = row.doubleAny("amount", "total") ?: 0.0
                    )
                )
            }
        }

        val billsJson = json.optJSONArray("split_bills") ?: JSONArray()
        val bills = buildList {
            for (i in 0 until billsJson.length()) {
                val row = billsJson.optJSONObject(i) ?: continue
                val id = row.longAny("id", "split_bill_id") ?: continue
                add(
                    SplitBillInfo(
                        id = id,
                        label = row.optText("label", "name") ?: "Part ${i + 1}",
                        amount = row.doubleAny("amount", "total") ?: 0.0,
                        paidAmount = row.doubleAny("paid_amount", "amount_paid") ?: 0.0,
                        amountDue = row.doubleAny("amount_due", "due") ?: 0.0,
                        status = row.optText("status") ?: "pending"
                    )
                )
            }
        }

        val tablesJson = json.optJSONArray("session_tables") ?: JSONArray()
        val sessionTables = buildList {
            for (i in 0 until tablesJson.length()) {
                val row = tablesJson.optJSONObject(i) ?: continue
                val id = row.longAny("id", "table_id") ?: continue
                add(SessionTableInfo(id, row.optText("label", "name") ?: "Table $id"))
            }
        }

        val candidatesJson = json.optJSONArray("merge_candidates") ?: JSONArray()
        val candidates = buildList {
            for (i in 0 until candidatesJson.length()) {
                val row = candidatesJson.optJSONObject(i) ?: continue
                val tableId = row.longAny("table_id", "id") ?: continue
                val orderIdsJson = row.optJSONArray("order_ids") ?: JSONArray()
                val orderIds = buildList {
                    for (j in 0 until orderIdsJson.length()) {
                        orderIdsJson.optLong(j).takeIf { it > 0 }?.let { add(it) }
                    }
                }
                add(
                    MergeCandidate(
                        tableId = tableId,
                        label = row.optText("label", "name") ?: "Table $tableId",
                        orderIds = orderIds,
                        amountDue = row.doubleAny("amount_due", "due") ?: 0.0,
                        alreadyMerged = row.optBoolean("already_merged", false)
                    )
                )
            }
        }

        val groupJson = json.optJSONArray("group_orders") ?: JSONArray()
        val groupOrders = buildList {
            for (i in 0 until groupJson.length()) {
                val row = groupJson.optJSONObject(i) ?: continue
                val orderId = row.longAny("order_id", "id") ?: continue
                add(
                    GroupOrderDue(
                        orderId = orderId,
                        orderNumber = row.optText("order_number", "code") ?: orderId.toString(),
                        tableId = row.longAny("table_id"),
                        tableLabel = row.optText("table_label"),
                        total = row.doubleAny("total", "grand_total") ?: 0.0,
                        amountPaid = row.doubleAny("amount_paid") ?: 0.0,
                        amountDue = row.doubleAny("amount_due", "due") ?: 0.0,
                        status = row.optText("status") ?: "unknown"
                    )
                )
            }
        }

        val caps = json.optJSONObject("capabilities") ?: JSONObject()
        return BillingContext(
            orderId = json.longAny("order_id", "id") ?: 0L,
            orderNumber = json.optText("order_number", "code") ?: "",
            total = json.doubleAny("total", "grand_total") ?: 0.0,
            amountPaid = json.doubleAny("amount_paid") ?: 0.0,
            amountDue = json.doubleAny("amount_due", "due") ?: 0.0,
            settlementStatus = json.optText("settlement_status", "status") ?: "unknown",
            operationalStatus = json.optText("operational_status", "order_status") ?: "placed",
            tableId = json.longAny("table_id"),
            diningSessionId = json.longAny("dining_session_id", "session_id"),
            items = lines,
            splitBills = bills,
            sessionTables = sessionTables,
            groupOrders = groupOrders,
            groupAmountDue = json.doubleAny("group_amount_due") ?: groupOrders.sumOf { it.amountDue },
            mergeCandidates = candidates,
            capabilities = BillingCapabilities(
                splitEqual = caps.optBoolean("split_equal", false),
                splitCustom = caps.optBoolean("split_custom", false),
                splitItems = caps.optBoolean("split_items", false),
                splitPay = caps.optBoolean("split_pay", false),
                mergeTables = caps.optBoolean("merge_tables", false)
            )
        )
    }

    suspend fun cashRegisters(token: String): List<CashRegister> = withContext(Dispatchers.IO) {
        val json = request("pos/cash-register/registers", token = token)
        val array = findArrayDeep(json, setOf("registers", "data")) ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.longAny("id", "cash_register_id", "register_id") ?: continue
                add(CashRegister(id, obj.optText("name", "register_name", "title") ?: "Caisse $id"))
            }
        }
    }

    suspend fun cashDenominations(token: String): List<CashDenomination> = withContext(Dispatchers.IO) {
        val json = request("pos/cash-register/denominations", token = token)
        val array = findArrayDeep(json, setOf("denominations", "data")) ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val value = obj.doubleAny("value", "amount", "denomination") ?: continue
                val label = obj.optText("label", "name") ?: String.format(Locale.FRANCE, "%.2f €", value)
                add(CashDenomination(label, value))
            }
        }.sortedBy { it.value }
    }

    suspend fun activeCashSession(token: String): CashSession? = withContext(Dispatchers.IO) {
        val json = try {
            request("pos/cash-register/sessions/active", token = token)
        } catch (e: CookitApiException) {
            if (e.statusCode == 404) return@withContext null else throw e
        }
        val obj = findObjectDeep(json, setOf("session", "data")) ?: json
        val id = obj.longAny("id", "session_id") ?: return@withContext null
        CashSession(
            id = id,
            registerId = obj.longAny("cash_register_id", "register_id"),
            status = obj.optText("status") ?: "active",
            openingAmount = obj.doubleAny("opening_amount", "opening_balance", "opening_cash"),
            expectedAmount = obj.doubleAny("expected_amount", "expected_balance", "expected_cash")
        )
    }

    suspend fun openCashSession(token: String, registerId: Long, openingAmount: Double): CashSession = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("cash_register_id", registerId)
            .put("register_id", registerId)
            .put("opening_amount", openingAmount)
            .put("opening_balance", openingAmount)
        val json = request("pos/cash-register/sessions/open", method = "POST", token = token, body = body)
        val obj = findObjectDeep(json, setOf("session", "data")) ?: json
        val id = obj.longAny("id", "session_id") ?: throw CookitApiException(200, "Session ouverte mais identifiant introuvable.")
        CashSession(id, obj.longAny("cash_register_id", "register_id") ?: registerId, obj.optText("status") ?: "active", openingAmount)
    }

    private fun parseServerEpochMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(value).toInstant().toEpochMilli() }

        val candidates = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
        )
        for (formatter in candidates) {
            try {
                return LocalDateTime.parse(value, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    private fun minutesSince(epochMs: Long?): Int {
        if (epochMs == null) return 0
        return ((System.currentTimeMillis() - epochMs).coerceAtLeast(0L) / 60_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun extractMediaCandidate(obj: JSONObject): String? {
        obj.optText(
            "item_photo_url", "itemPhotoUrl", "item_photo", "itemPhoto",
            "image_url", "imageUrl", "item_image", "itemImage", "photo_url", "thumbnail_url"
        )?.let { return it }
        for (key in listOf("image", "photo", "thumbnail", "media")) {
            val nested = obj.optJSONObject(key)
            nested?.optText("url", "full_url", "path", "src", "original_url")?.let { return it }
            val scalar = obj.opt(key)
            if (scalar is String && scalar.isNotBlank()) return scalar
        }
        return null
    }

    private fun normalizeMediaUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val apiMarker = "/api/application-integration/"
        val origin = baseUrl.substringBefore(apiMarker).trimEnd('/')
        return origin + "/" + value.trimStart('/')
    }

    private fun request(
        path: String,
        method: String = "GET",
        token: String? = null,
        body: JSONObject? = null
    ): JSONObject {
        val connection = (URL(baseUrl + path.trimStart('/')).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", languageCode)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) doOutput = true
        }

        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            }.orEmpty()

            if (code !in 200..299) {
                val message = extractApiErrorMessage(text, code)
                throw CookitApiException(code, message, text)
            }

            if (text.isBlank()) return JSONObject()
            val trimmed = text.trim()
            return if (trimmed.startsWith("[")) JSONObject().put("data", JSONArray(trimmed)) else JSONObject(trimmed)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractApiErrorMessage(text: String, code: Int): String {
        if (text.isBlank()) return "Erreur HTTP $code"

        return runCatching {
            val errorJson = JSONObject(text)
            val validation = errorJson.optJSONObject("errors")
            if (validation != null) {
                val messages = mutableListOf<String>()
                val keys = validation.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val raw = validation.opt(key)
                    when (raw) {
                        is JSONArray -> if (raw.length() > 0) messages += raw.optString(0)
                        is String -> messages += raw
                    }
                }
                messages.firstOrNull { it.isNotBlank() }?.let { return@runCatching it }
            }

            val rawMessage = errorJson.optText("message", "error")
            if (rawMessage.isNullOrBlank()) {
                "Erreur HTTP $code"
            } else if (rawMessage.startsWith("messages.") || rawMessage.contains("::")) {
                "La commande n'a pas pu être enregistrée (HTTP $code)."
            } else {
                rawMessage
            }
        }.getOrDefault("Erreur HTTP $code")
    }

    private fun mapRole(raw: String): PosRole {
        val normalized = raw.lowercase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
        return when {
            "admin" in normalized || "owner" in normalized -> PosRole.ADMINISTRATOR
            "manager" in normalized || "gerant" in normalized || "responsable" in normalized -> PosRole.MANAGER
            "waiter" in normalized || "serveur" in normalized -> PosRole.WAITER
            "kitchen" in normalized || "cuisine" in normalized || "chef" in normalized -> PosRole.KITCHEN
            "delivery" in normalized || "driver" in normalized || "livreur" in normalized -> PosRole.DELIVERY
            else -> PosRole.CASHIER
        }
    }

    private fun defaultPolicy(role: PosRole): NativePolicy = NativePolicy(
        profile = role,
        cashSessionRequired = role == PosRole.CASHIER,
        canManageSettings = role == PosRole.ADMINISTRATOR || role == PosRole.MANAGER,
        canManagePrinters = role == PosRole.ADMINISTRATOR || role == PosRole.MANAGER,
        canUsePos = role != PosRole.KITCHEN && role != PosRole.DELIVERY,
        canViewKds = role != PosRole.DELIVERY,
        canViewDelivery = role != PosRole.KITCHEN
    )

    private fun mapOrderType(raw: String): OrderType = when (raw.lowercase(Locale.ROOT)) {
        "takeaway", "take_away", "pickup", "takeout" -> OrderType.TAKEAWAY
        "delivery" -> OrderType.DELIVERY
        else -> OrderType.DINE_IN
    }

    private fun channelLabel(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "customer", "qr", "qr_table", "table_qr" -> "QR Table"
        "web", "website", "online" -> "Site web"
        "platform" -> "Plateforme"
        "delivery" -> "Delivery"
        else -> "POS"
    }

    private fun humanStatus(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "placed", "new", "pending", "pending_verification" -> "Nouveau"
        "confirmed", "pending_confirmation" -> "Confirmé"
        "preparing", "in_kitchen", "cooking" -> "En cuisine"
        "food_ready", "ready", "ready_for_pickup" -> "Prêt"
        "picked_up" -> "Pris en charge"
        "served" -> "Servi"
        "out_for_delivery" -> "En livraison"
        "reached_destination" -> "Arrivé"
        "delivered", "completed" -> "Livré"
        "cancelled", "canceled" -> "Annulé"
        else -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun emojiForCategory(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            "boisson" in n || "drink" in n -> "🥤"
            "dessert" in n -> "🍰"
            "grill" in n -> "🔥"
            "entrée" in n || "salad" in n -> "🥗"
            else -> "🍽️"
        }
    }

    private fun emojiForProduct(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            "poulet" in n || "chicken" in n -> "🍗"
            "agneau" in n || "lamb" in n -> "🍖"
            "salade" in n -> "🥗"
            "thé" in n || "tea" in n -> "🫖"
            "jus" in n || "juice" in n -> "🥤"
            "dessert" in n || "cake" in n -> "🍰"
            else -> "🍽️"
        }
    }
}

private fun JSONObject.optText(vararg keys: String): String? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val value = optString(key).trim()
        if (value.isNotEmpty() && value != "null") return value
    }
    return null
}


private fun extractVatRate(obj: JSONObject): Double? {
    obj.doubleAny("vat_rate", "vat_percentage", "vat_percent", "tax_rate", "tax_percentage", "tax_percent", "tax")?.let { return it }
    for (key in arrayOf("vat", "tax", "tax_rate", "vat_rate")) {
        val nested = obj.optJSONObject(key) ?: continue
        nested.doubleAny("rate", "percentage", "percent", "value", "vat_rate", "tax_rate", "tax_percent")?.let { return it }
    }

    // Cookit RestApi serializes MenuItem::taxes as a JSON array. Belgian fiscal
    // items must resolve to one effective VAT rate for the GKS sale line. Keep
    // this fail-closed when several distinct rates are returned.
    val taxes = obj.optJSONArray("taxes")
    if (taxes != null) {
        val rates = buildList {
            for (index in 0 until taxes.length()) {
                val tax = taxes.optJSONObject(index) ?: continue
                val rate = tax.doubleAny("tax_percent", "rate", "percentage", "percent", "vat_rate", "tax_rate")
                    ?: continue
                add(rate)
            }
        }.distinctBy { kotlin.math.round(it * 10000.0) / 10000.0 }
        if (rates.size == 1) return rates.first()
    }
    return null
}

private fun extractVatLabel(obj: JSONObject): String? {
    obj.optText("vat_label", "tax_label", "vat_code", "tax_code")?.trim()?.takeIf { it.isNotBlank() }?.let { return it.uppercase(Locale.ROOT) }
    for (key in arrayOf("vat", "tax", "tax_rate", "vat_rate")) {
        val nested = obj.optJSONObject(key) ?: continue
        nested.optText("label", "code", "vat_label", "tax_label")?.trim()?.takeIf { it.isNotBlank() }?.let { return it.uppercase(Locale.ROOT) }
    }

    val taxes = obj.optJSONArray("taxes")
    if (taxes != null) {
        val labels = buildList {
            for (index in 0 until taxes.length()) {
                val tax = taxes.optJSONObject(index) ?: continue
                val label = tax.optText("vat_label", "tax_label", "vat_code", "tax_code", "code")
                    ?.trim()?.uppercase(Locale.ROOT)
                    ?.takeIf { it in setOf("A", "B", "C", "D", "X") }
                    ?: continue
                add(label)
            }
        }.distinct()
        if (labels.size == 1) return labels.first()
    }
    return null
}

private fun JSONObject.longAny(vararg keys: String): Long? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val raw = opt(key)
        when (raw) {
            is Number -> return raw.toLong()
            is String -> raw.toLongOrNull()?.let { return it }
        }
    }
    return null
}

private fun JSONObject.doubleAny(vararg keys: String): Double? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        val raw = opt(key)
        when (raw) {
            is Number -> return raw.toDouble()
            is String -> raw.replace(',', '.').toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

private fun JSONObject.boolAny(vararg keys: String): Boolean? {
    for (key in keys) {
        if (!has(key) || isNull(key)) continue
        return when (val raw = opt(key)) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", true) || raw == "1" || raw.equals("active", true)
            else -> null
        }
    }
    return null
}

private fun findStringDeep(value: Any?, keys: Set<String>): String? {
    when (value) {
        is JSONObject -> {
            for (key in keys) {
                if (value.has(key) && !value.isNull(key)) {
                    val result = value.optString(key).trim()
                    if (result.isNotEmpty() && result != "null") return result
                }
            }
            val names = value.keys()
            while (names.hasNext()) {
                findStringDeep(value.opt(names.next()), keys)?.let { return it }
            }
        }
        is JSONArray -> for (i in 0 until value.length()) {
            findStringDeep(value.opt(i), keys)?.let { return it }
        }
    }
    return null
}

private fun findObjectDeep(value: Any?, keys: Set<String>): JSONObject? {
    when (value) {
        is JSONObject -> {
            for (key in keys) value.optJSONObject(key)?.let { return it }
            val names = value.keys()
            while (names.hasNext()) {
                findObjectDeep(value.opt(names.next()), keys)?.let { return it }
            }
        }
        is JSONArray -> for (i in 0 until value.length()) {
            findObjectDeep(value.opt(i), keys)?.let { return it }
        }
    }
    return null
}

private fun findArrayDeep(value: Any?, keys: Set<String>): JSONArray? {
    when (value) {
        is JSONObject -> {
            for (key in keys) value.optJSONArray(key)?.let { return it }
            val names = value.keys()
            while (names.hasNext()) {
                findArrayDeep(value.opt(names.next()), keys)?.let { return it }
            }
        }
        is JSONArray -> return value
    }
    return null
}

private fun JSONArray.firstObject(): JSONObject? = if (length() > 0) optJSONObject(0) else null
private fun firstStringFromArray(array: JSONArray?): String? = array?.takeIf { it.length() > 0 }?.optString(0)?.takeIf { it.isNotBlank() }
