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

        val restaurantName = restaurantObj?.optText("name", "restaurant_name")
            ?: json.optText("restaurant_name")
            ?: "Cookit Restaurant"

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
                branch = branchName
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
                add(Product(id, categoryId, name, description, price, emojiForProduct(name), available, imageUrl))
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
                val codeRaw = obj.optText("order_number", "order_no", "code", "uuid") ?: id.toString()
                val channel = channelLabel(obj.optText("placed_via", "channel", "source") ?: "POS")
                val type = mapOrderType(obj.optText("order_type", "type") ?: "dine_in")
                val status = humanStatus(obj.optText("order_status", "status") ?: "placed")
                val total = obj.doubleAny("grand_total", "total", "amount", "total_amount") ?: 0.0
                val customerObj = obj.optJSONObject("customer")
                val tableObj = obj.optJSONObject("table")
                val customer = customerObj?.optText("name", "full_name")
                    ?: obj.optText("customer_name")
                    ?: tableObj?.optText("table_name", "name")
                    ?: "Client"
                val table = tableObj?.optText("table_name", "name") ?: obj.optText("table_name")

                add(
                    PosOrder(
                        id = id,
                        code = if (codeRaw.startsWith("#")) codeRaw else "#$codeRaw",
                        channel = channel,
                        type = type,
                        status = status,
                        total = total,
                        customer = customer,
                        table = table,
                        minutesAgo = 0,
                        unread = false
                    )
                )
            }
        }
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
        tableId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) throw CookitApiException(422, "Panier vide")
        val items = JSONArray()
        lines.forEach { line ->
            items.put(
                JSONObject()
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

    suspend fun payOrderCash(token: String, orderId: Long, amount: Double) = withContext(Dispatchers.IO) {
        val payments = JSONArray().put(JSONObject().put("amount", amount).put("method", "cash"))
        request(
            "pos/orders/$orderId/pay",
            method = "POST",
            token = token,
            body = JSONObject().put("payments", payments)
        )
        Unit
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

    private fun extractMediaCandidate(obj: JSONObject): String? {
        obj.optText("image_url", "imageUrl", "item_image", "itemImage", "photo_url", "thumbnail_url")?.let { return it }
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
                val message = runCatching {
                    val errorJson = JSONObject(text)
                    errorJson.optText("message", "error") ?: "Erreur HTTP $code"
                }.getOrDefault("Erreur HTTP $code")
                throw CookitApiException(code, message, text)
            }

            if (text.isBlank()) return JSONObject()
            val trimmed = text.trim()
            return if (trimmed.startsWith("[")) JSONObject().put("data", JSONArray(trimmed)) else JSONObject(trimmed)
        } finally {
            connection.disconnect()
        }
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
        "placed", "new", "pending" -> "Nouveau"
        "confirmed" -> "Confirmé"
        "preparing" -> "En cuisine"
        "food_ready", "ready", "ready_for_pickup" -> "Prêt"
        "served" -> "Servi"
        "out_for_delivery" -> "En livraison"
        "delivered" -> "Livré"
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
