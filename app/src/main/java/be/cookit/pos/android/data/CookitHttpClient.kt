package be.cookit.pos.android.data

import be.cookit.pos.android.BuildConfig
import be.cookit.pos.android.domain.*
import be.cookit.pos.android.data.fiscal.FiscalShadowOrderResolution
import be.cookit.pos.android.data.fiscal.FiscalShadowResolutionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    val policy: NativePolicy,
    val certificationCapabilities: NativeCertificationCapabilities = NativeCertificationCapabilities()
)


data class NativePosMachineBinding(
    val id: Long,
    val branchId: Long,
    val publicId: String,
    val status: String,
    val deviceId: String,
    val platform: String?,
    val runtimeId: String?,
    val terminalId: String?
)

data class NativePosDeviceCheck(
    val needsRegistration: Boolean,
    val machine: NativePosMachineBinding?
)

class CookitHttpClient {
    var languageCode: String = "fr"

    /*
     * Native CashRegister endpoints are POS/device scoped by the backend.
     *
     * This value is the permanent Android device identity owned by
     * FiscalRuntimeRepository. It is transport metadata only; it never
     * participates in fiscal payload construction.
     */
    var nativeDeviceId: String = ""
        private set

    private val baseUrl = BuildConfig.COOKIT_API_BASE_URL.trimEnd('/') + "/"
    private val originUrl = baseUrl.substringBefore("/api/application-integration/").trimEnd('/') + "/"

    fun bindNativeDeviceId(deviceId: String?) {
        nativeDeviceId =
            deviceId
                ?.trim()
                .orEmpty()
    }

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

        /*
         * Role-derived policy remains useful for the existing native UX,
         * but CashRegister manager/report authority must come from the
         * real Cloud permissions exposed by Spatie.
         *
         * Failure is deliberately fail-closed for the two sensitive
         * CashRegister capabilities.
         */
        val permissionJson =
            runCatching {
                request(
                    "platform/permissions",
                    token = token
                )
            }.getOrNull()

        val cloudPermissions =
            mutableSetOf<String>()

        permissionJson
            ?.optJSONArray(
                "permissions"
            )
            ?.let {
                array ->

                for (
                    index in 0 until array.length()
                ) {
                    val permission =
                        array.optString(
                            index
                        ).trim()

                    if (
                        permission.isNotBlank()
                    ) {
                        cloudPermissions +=
                            permission
                    }
                }
            }

        // A4 capabilities are authoritative on the dedicated native bootstrap.
        // Keep platform/config as the established Android identity source and fail
        // closed if the bootstrap is temporarily unavailable.
        val nativeBootstrap = runCatching {
            request("native/bootstrap", token = token)
        }.getOrNull()
        val certificationJson = nativeBootstrap?.optJSONObject("certification_capabilities")
            ?: json.optJSONObject("certification_capabilities")
        val certificationCapabilities = NativeCertificationCapabilities(
            contractVersion = certificationJson?.optText("contract_version") ?: "",
            manualDiscount = certificationJson
                ?.optJSONObject("manual_discount")
                ?.optBoolean("android_expose", false)
                ?: false,
            tip = certificationJson
                ?.optJSONObject("tip")
                ?.optBoolean("android_expose", false)
                ?: false,
            loyaltyPoints = certificationJson
                ?.optJSONObject("loyalty_points")
                ?.optBoolean("android_expose", false)
                ?: false,
            stampRewards = certificationJson
                ?.optJSONObject("stamp_rewards")
                ?.optBoolean("android_expose", false)
                ?: false,
            modifiers = certificationJson
                ?.optJSONObject("modifiers")
                ?.optBoolean("android_expose", false)
                ?: false
        )

        val basePolicy =
            defaultPolicy(
                role
            )

        val canApproveCashRegister =
            cloudPermissions.any {
                permission ->
                permission.equals(
                    "Approve Cash Register",
                    ignoreCase = true
                )
            }

        val canViewCashRegisterReports =
            cloudPermissions.any {
                permission ->
                permission.equals(
                    "View Cash Register Reports",
                    ignoreCase = true
                )
            }

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
            policy =
                basePolicy.copy(
                    canApproveCashRegister =
                        canApproveCashRegister,
                    canViewCashRegisterReports =
                        canViewCashRegisterReports
                ),
            certificationCapabilities = certificationCapabilities
        )
    }

    suspend fun checkNativePosDevice(
        token: String,
        branchId: Long,
        deviceId: String
    ): NativePosDeviceCheck = withContext(Dispatchers.IO) {
        val encodedDeviceId =
            URLEncoder.encode(
                deviceId,
                StandardCharsets.UTF_8.name()
            )

        val json = request(
            "pos/multi-pos/check" +
                "?device_id=$encodedDeviceId" +
                "&branch_id=$branchId",
            token = token
        )

        NativePosDeviceCheck(
            needsRegistration =
                json.optBoolean(
                    "needs_registration",
                    false
                ),
            machine =
                parseNativePosMachine(
                    json.optJSONObject("data")
                )
        )
    }

    suspend fun registerNativePosDevice(
        token: String,
        branchId: Long,
        deviceId: String,
        runtimeId: String,
        terminalId: String,
        alias: String,
        deviceMetadata: JSONObject
    ): NativePosMachineBinding = withContext(Dispatchers.IO) {
        val body =
            JSONObject()
                .put("branch_id", branchId)
                .put("alias", alias)
                .put("device_id", deviceId)
                .put("platform", "android")
                .put("runtime_id", runtimeId)
                .put("terminal_id", terminalId)
                .put(
                    "device_metadata",
                    deviceMetadata
                )

        val json = request(
            "pos/multi-pos/register",
            method = "POST",
            token = token,
            body = body
        )

        parseNativePosMachine(
            json.optJSONObject("data")
        ) ?: throw CookitApiException(
            200,
            "Cookit MultiPOS registration returned no machine"
        )
    }

    private fun parseNativePosMachine(
        obj: JSONObject?
    ): NativePosMachineBinding? {
        if (obj == null) {
            return null
        }

        val id =
            obj.optLong(
                "id",
                0L
            )

        val branchId =
            obj.optLong(
                "branch_id",
                0L
            )

        val deviceId =
            obj.optString(
                "device_id"
            ).trim()

        if (
            id <= 0L
            || branchId <= 0L
            || deviceId.isBlank()
        ) {
            return null
        }

        return NativePosMachineBinding(
            id = id,
            branchId = branchId,
            publicId =
                obj.optString(
                    "public_id"
                ).trim(),
            status =
                obj.optString(
                    "status"
                )
                    .trim()
                    .lowercase(Locale.ROOT),
            deviceId = deviceId,
            platform =
                obj.optString(
                    "platform"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    },
            runtimeId =
                obj.optString(
                    "runtime_id"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    },
            terminalId =
                obj.optString(
                    "terminal_id"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
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
                val hasModifiers = (obj.longAny("modifier_groups_count") ?: 0L) > 0L ||
                    ((obj.optJSONArray("modifier_groups")?.length() ?: 0) > 0)
                add(
                    Product(
                        id = id,
                        categoryId = categoryId,
                        name = name,
                        description = description,
                        price = price,
                        emoji = emojiForProduct(name),
                        available = available,
                        imageUrl = imageUrl,
                        vatRate = vatRate,
                        vatLabel = vatLabel,
                        hasModifiers = hasModifiers
                    )
                )
            }
        }.distinctBy { it.id }

        val normalizedCategories = if (categories.isNotEmpty()) {
            listOf(Category(0, "Tous", "✨")) + categories
        } else {
            emptyList()
        }

        CatalogSnapshot(normalizedCategories, products)
    }

    suspend fun modifierGroups(
        token: String,
        productId: Long,
        orderType: OrderType
    ): List<NativeModifierGroup> = withContext(Dispatchers.IO) {
        val orderTypeId = orderTypeId(token, orderType)
        val query = buildString {
            append("pos/items/")
            append(productId)
            append("/modifier-groups")
            orderTypeId?.let { append("?order_type_id=").append(it) }
        }
        val json = request(query, token = token)
        val array = findArrayDeep(json, setOf("data", "modifier_groups", "groups")) ?: JSONArray()

        buildList {
            for (index in 0 until array.length()) {
                val groupJson = array.optJSONObject(index) ?: continue
                val groupId = groupJson.longAny("id", "modifier_group_id") ?: continue
                val pivot = groupJson.optJSONObject("pivot") ?: JSONObject()

                // Android currently has no variation selector. Variation-only modifier
                // groups therefore stay fail-closed until that UI exists.
                val variationId = pivot.longAny("menu_item_variation_id", "variation_id")
                if (variationId != null && variationId > 0L) continue

                val required = pivot.boolAny("is_required", "required") ?: false
                val allowMultiple = pivot.boolAny("allow_multiple_selection", "allow_multiple", "multiple") ?: false
                val groupName = localizedModifierText(groupJson.opt("name"), "Options")
                val optionsJson = groupJson.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (optionIndex in 0 until optionsJson.length()) {
                        val optionJson = optionsJson.optJSONObject(optionIndex) ?: continue
                        val optionId = optionJson.longAny("id", "modifier_option_id") ?: continue
                        val available = optionJson.boolAny("is_available", "available") ?: true
                        if (!available) continue
                        val price = (optionJson.doubleAny("price", "final_price", "calculated_price") ?: 0.0)
                            .takeIf { it.isFinite() && it >= 0.0 }
                            ?: 0.0
                        add(
                            NativeModifierOption(
                                id = optionId,
                                groupId = groupId,
                                name = localizedModifierText(optionJson.opt("name"), "Option $optionId"),
                                price = price,
                                available = true,
                                preselected = optionJson.boolAny("is_preselected", "preselected") ?: false
                            )
                        )
                    }
                }
                add(
                    NativeModifierGroup(
                        id = groupId,
                        name = groupName,
                        required = required,
                        allowMultiple = allowMultiple,
                        options = options
                    )
                )
            }
        }
    }

    private suspend fun orderTypeId(token: String, type: OrderType): Long? {
        val json = request("pos/order-types", token = token)
        val array = findArrayDeep(json, setOf("data", "order_types")) ?: JSONArray()

        fun canonicalOrderType(row: JSONObject): String = listOf(
            "slug",
            "type",
            "order_type_name",
            "name",
            "label"
        ).mapNotNull { key ->
            row.optText(key)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.joinToString(" ")
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')

        fun matches(candidate: String): Boolean {
            val compact = candidate.replace(" ", "")
            return when (type) {
                OrderType.DINE_IN ->
                    "dine in" in candidate ||
                        "dinein" in compact ||
                        "sur place" in candidate ||
                        "eatin" in compact

                OrderType.TAKEAWAY ->
                    "pickup" in compact ||
                        "takeaway" in compact ||
                        "takeout" in compact ||
                        "emporter" in compact ||
                        "collect" in compact

                OrderType.DELIVERY ->
                    "delivery" in compact ||
                        "livraison" in compact
            }
        }

        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            if (matches(canonicalOrderType(row))) {
                return row.longAny("id", "order_type_id")
            }
        }
        return null
    }

    private fun localizedModifierText(value: Any?, fallback: String): String {
        fun fromObject(obj: JSONObject): String? {
            val preferred = listOf(languageCode, "fr", "en", "nl", "de")
            preferred.forEach { code ->
                obj.optString(code, "").trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                obj.optString(keys.next(), "").trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
            return null
        }

        val resolved = when (value) {
            is JSONObject -> fromObject(value)
            is String -> {
                val trimmed = value.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    runCatching { fromObject(JSONObject(trimmed)) }.getOrNull() ?: trimmed
                } else trimmed
            }
            else -> value?.toString()?.trim().orEmpty()
        }
        return resolved?.takeIf { it.isNotEmpty() } ?: fallback
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

    suspend fun fiscalShadowResolution(token: String, orderId: Long): FiscalShadowOrderResolution = withContext(Dispatchers.IO) {
        val json = requestAbsolute(
            originUrl + "api/v1/fiscal/orders/$orderId/shadow-resolution",
            token = token
        )
        FiscalShadowResolutionParser.parse(json)
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
                val orderItemId = item.longAny("id", "order_item_id")
                val explicitFree = item.boolAny("is_free_item_from_stamp", "free_item")
                val freeItem = explicitFree
                    ?: (amount != null && kotlin.math.abs(amount) < 0.0001 && price > 0.0)
                val modifiersJson = item.optJSONArray("modifiers") ?: JSONArray()
                val modifiers = buildList {
                    for (modifierIndex in 0 until modifiersJson.length()) {
                        val modifier = modifiersJson.optJSONObject(modifierIndex) ?: continue
                        val modifierId = modifier.longAny("id", "modifier_option_id") ?: continue
                        add(
                            SelectedModifier(
                                id = modifierId,
                                groupId = modifier.longAny("modifier_group_id", "group_id") ?: 0L,
                                name = localizedModifierText(
                                    modifier.opt("name") ?: modifier.opt("modifier_option_name"),
                                    "Option $modifierId"
                                ),
                                price = (modifier.doubleAny("price", "modifier_option_price") ?: 0.0)
                                    .takeIf { it.isFinite() && it >= 0.0 }
                                    ?: 0.0
                            )
                        )
                    }
                }
                add(
                    RemoteOrderLine(
                        menuItemId = menuItemId,
                        quantity = qty,
                        price = price,
                        name = name,
                        categoryId = categoryId,
                        vatRate = vatRate,
                        vatLabel = vatLabel,
                        orderItemId = orderItemId,
                        amount = amount,
                        freeItem = freeItem,
                        modifiers = modifiers
                    )
                )
            }
        }

        val financials = obj.optJSONObject("financials")
        val cartSummary = obj.optJSONObject("cart")?.optJSONObject("summary")
        val effectiveTotal = obj.doubleAny("grand_total", "total", "total_amount")
            ?: financials?.doubleAny("total", "grand_total")
            ?: cartSummary?.doubleAny("grand_total", "total")
            ?: lines.sumOf { line ->
                (line.price + line.modifiers.sumOf { it.price }) * line.quantity
            }

        val type = mapOrderType(obj.optText("order_type", "type") ?: "dine_in")
        val customerObj = obj.optJSONObject("customer")
        val rawOrder = obj.optJSONObject("order")
        val commercial = parseCommercialSnapshot(
            source = financials ?: cartSummary ?: JSONObject(),
            orderId = obj.longAny("id", "order_id") ?: orderId,
            rawOrder = rawOrder,
            fallbackTotal = effectiveTotal
        )
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
            lines = lines,
            customerId = obj.longAny("customer_id") ?: customerObj?.longAny("id", "customer_id"),
            customerName = customerObj?.optText("name", "full_name"),
            customerPhone = customerObj?.optText("phone", "phone_number"),
            commercial = commercial
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
        deliveryExecutiveId: Long? = null,
        clientOrderUuid: String? = null
    ): Long = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) throw CookitApiException(422, "Panier vide")
        val items = JSONArray()
        lines.forEach { line ->
            val item = JSONObject()
                .put("id", line.product.id)
                .put("menu_item_id", line.product.id)
                .put("quantity", line.quantity)

            if (line.modifiers.isNotEmpty()) {
                val modifiers = JSONArray()
                line.modifiers
                    .map { it.id }
                    .distinct()
                    .forEach { modifierId ->
                        modifiers.put(
                            JSONObject()
                                .put("id", modifierId)
                        )
                    }
                item.put("modifiers", modifiers)
            }

            // Product/modifier prices are deliberately not authoritative on Android.
            // The backend resolves the item price and every selected modifier by ID.
            items.put(item)
        }
        val body = JSONObject()
            .put("order_type", when (type) {
                OrderType.DINE_IN -> "dine_in"
                OrderType.TAKEAWAY -> "pickup"
                OrderType.DELIVERY -> "delivery"
            })
            .put("placed_via", "pos")
            .put("items", items)

        clientOrderUuid
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { body.put("client_order_uuid", it) }

        val customer = JSONObject()
        customerName?.trim()?.takeIf { it.isNotEmpty() }?.let { customer.put("name", it) }
        customerPhone?.trim()?.takeIf { it.isNotEmpty() }?.let { customer.put("phone", it) }
        if (customer.length() > 0) body.put("customer", customer)

        if (type == OrderType.DINE_IN && tableId != null) body.put("table_id", tableId)
        if (type == OrderType.DELIVERY) {
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

    suspend fun updateCommercialAdjustments(
        token: String,
        orderId: Long,
        includeDiscount: Boolean = false,
        discountType: String? = null,
        discountValue: Double = 0.0,
        includeTip: Boolean = false,
        tipAmount: Double = 0.0,
        tipNote: String? = null
    ): CommercialSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (includeDiscount) {
            body.put(
                "discount",
                JSONObject()
                    .put("type", discountType ?: JSONObject.NULL)
                    .put("value", discountValue.coerceAtLeast(0.0))
            )
        }
        if (includeTip) {
            body.put(
                "tip",
                JSONObject()
                    .put("amount", tipAmount.coerceAtLeast(0.0))
                    .put("note", tipNote?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            )
        }
        val json = request(
            "pos/orders/$orderId/commercial-adjustments",
            method = "PATCH",
            token = token,
            body = body
        )
        parseCommercialSnapshot(
            source = json.optJSONObject("commercial") ?: json,
            orderId = orderId,
            fallbackTotal = json.optJSONObject("commercial")
                ?.optJSONObject("totals")
                ?.optDouble("grand_total", 0.0)
                ?: 0.0
        )
    }

    suspend fun loyaltySummary(token: String, orderId: Long): LoyaltySummary = withContext(Dispatchers.IO) {
        val json = request("pos/orders/$orderId/loyalty", token = token)
        parseLoyaltySummary(json.optJSONObject("data") ?: json)
    }

    suspend fun redeemLoyaltyPoints(
        token: String,
        orderId: Long,
        points: Int,
        clientOperationId: String
    ): LoyaltyMutationResult = withContext(Dispatchers.IO) {
        val json = request(
            "pos/orders/$orderId/loyalty/points/redeem",
            method = "POST",
            token = token,
            body = JSONObject()
                .put("points", points)
                .put("client_operation_id", clientOperationId)
        )
        parseLoyaltyMutation(json, orderId)
    }

    suspend fun removeLoyaltyPoints(
        token: String,
        orderId: Long,
        clientOperationId: String
    ): LoyaltyMutationResult = withContext(Dispatchers.IO) {
        val json = request(
            "pos/orders/$orderId/loyalty/points/remove",
            method = "POST",
            token = token,
            body = JSONObject().put("client_operation_id", clientOperationId)
        )
        parseLoyaltyMutation(json, orderId)
    }

    suspend fun redeemLoyaltyStamp(
        token: String,
        orderId: Long,
        ruleId: Long,
        clientOperationId: String
    ): LoyaltyMutationResult = withContext(Dispatchers.IO) {
        val json = request(
            "pos/orders/$orderId/loyalty/stamps/$ruleId/redeem",
            method = "POST",
            token = token,
            body = JSONObject().put("client_operation_id", clientOperationId)
        )
        parseLoyaltyMutation(json, orderId)
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

    private fun parseCommercialSnapshot(
        source: JSONObject,
        orderId: Long,
        rawOrder: JSONObject? = null,
        fallbackTotal: Double = 0.0
    ): CommercialSnapshot {
        val discountObject = source.optJSONObject("discount")
        val tipObject = source.optJSONObject("tip")
        val loyaltyObject = source.optJSONObject("loyalty")
        val totalsObject = source.optJSONObject("totals")

        val discountType = discountObject?.optText("type")
            ?: source.optText("discount_type")
            ?: rawOrder?.optText("discount_type")
        val discountValue = discountObject?.optDouble("value", Double.NaN)
            ?.takeUnless { it.isNaN() }
            ?: source.doubleAny("discount_value")
            ?: rawOrder?.doubleAny("discount_value")
            ?: 0.0
        val discountAmount = discountObject?.optDouble("amount", Double.NaN)
            ?.takeUnless { it.isNaN() }
            ?: source.doubleAny("discount_amount")
            ?: rawOrder?.doubleAny("discount_amount")
            ?: 0.0
        val tipAmount = tipObject?.optDouble("amount", Double.NaN)
            ?.takeUnless { it.isNaN() }
            ?: source.doubleAny("tip_amount")
            ?: rawOrder?.doubleAny("tip_amount")
            ?: 0.0
        val tipNote = tipObject?.optText("note")
            ?: rawOrder?.optText("tip_note")

        val subTotal = totalsObject?.optDouble("sub_total", Double.NaN)
            ?.takeUnless { it.isNaN() }
            ?: source.doubleAny("sub_total")
            ?: rawOrder?.doubleAny("sub_total")
            ?: 0.0
        val grandTotal = totalsObject?.optDouble("grand_total", Double.NaN)
            ?.takeUnless { it.isNaN() }
            ?: source.doubleAny("grand_total", "total")
            ?: rawOrder?.doubleAny("total")
            ?: fallbackTotal

        return CommercialSnapshot(
            contractVersion = source.optText("contract_version") ?: "a4.1",
            orderId = source.longAny("order_id", "id") ?: orderId,
            discount = CommercialDiscountState(
                type = discountType,
                value = discountValue,
                amount = discountAmount
            ),
            tip = CommercialTipState(
                amount = tipAmount,
                note = tipNote
            ),
            loyalty = CommercialLoyaltyState(
                pointsRedeemed = loyaltyObject?.optInt("points_redeemed", rawOrder?.optInt("loyalty_points_redeemed", 0) ?: 0)
                    ?: rawOrder?.optInt("loyalty_points_redeemed", 0)
                    ?: 0,
                discountAmount = loyaltyObject?.optDouble("discount_amount", Double.NaN)
                    ?.takeUnless { it.isNaN() }
                    ?: source.doubleAny("loyalty_discount_amount")
                    ?: rawOrder?.doubleAny("loyalty_discount_amount")
                    ?: 0.0,
                stampDiscountAmount = loyaltyObject?.optDouble("stamp_discount_amount", Double.NaN)
                    ?.takeUnless { it.isNaN() }
                    ?: source.doubleAny("stamp_discount_amount")
                    ?: rawOrder?.doubleAny("stamp_discount_amount")
                    ?: 0.0,
                stampDiscountEmbeddedInItems = loyaltyObject?.optBoolean("stamp_discount_embedded_in_items", true) ?: true
            ),
            totals = CommercialTotals(
                subTotal = subTotal,
                chargesTotal = totalsObject?.optDouble("charges_total", 0.0)
                    ?: source.doubleAny("charges_total")
                    ?: 0.0,
                taxTotal = totalsObject?.optDouble("tax_total", 0.0)
                    ?: source.doubleAny("tax_total")
                    ?: 0.0,
                deliveryFee = totalsObject?.optDouble("delivery_fee", 0.0)
                    ?: source.doubleAny("delivery_fee")
                    ?: 0.0,
                grandTotal = grandTotal
            )
        )
    }

    private fun parseLoyaltyOrderState(obj: JSONObject?): LoyaltyOrderState? {
        obj ?: return null
        val id = obj.longAny("id", "order_id") ?: return null
        return LoyaltyOrderState(
            id = id,
            customerId = obj.longAny("customer_id"),
            status = obj.optText("status") ?: "",
            settlementStatus = obj.optText("settlement_status") ?: "",
            subtotal = obj.doubleAny("subtotal", "sub_total") ?: 0.0,
            total = obj.doubleAny("total", "grand_total") ?: 0.0,
            manualDiscountAmount = obj.doubleAny("manual_discount_amount", "discount_amount") ?: 0.0,
            loyaltyPointsRedeemed = obj.optInt("loyalty_points_redeemed", 0),
            loyaltyDiscountAmount = obj.doubleAny("loyalty_discount_amount") ?: 0.0,
            stampDiscountAmount = obj.doubleAny("stamp_discount_amount") ?: 0.0,
            freeStampItemCount = obj.optInt("free_stamp_item_count", 0)
        )
    }

    private fun parseLoyaltySummary(obj: JSONObject): LoyaltySummary {
        val customerObj = obj.optJSONObject("customer")
        val pointsObj = obj.optJSONObject("points") ?: JSONObject()
        val stampsObj = obj.optJSONObject("stamps") ?: JSONObject()
        val rulesJson = stampsObj.optJSONArray("rules") ?: JSONArray()
        val rules = buildList {
            for (index in 0 until rulesJson.length()) {
                val row = rulesJson.optJSONObject(index) ?: continue
                val ruleId = row.longAny("rule_id", "id") ?: continue
                add(
                    LoyaltyStampRuleSummary(
                        ruleId = ruleId,
                        menuItemId = row.longAny("menu_item_id") ?: 0L,
                        menuItemName = row.optText("menu_item_name") ?: "",
                        stampsRequired = row.optInt("stamps_required", 0),
                        availableStamps = row.optInt("available_stamps", 0),
                        canRedeem = row.optBoolean("can_redeem", false),
                        eligibleQuantity = row.optInt("eligible_quantity", 0),
                        redeemedQuantity = row.optInt("redeemed_quantity", 0),
                        remainingEligibleQuantity = row.optInt("remaining_eligible_quantity", 0),
                        redeemableQuantity = row.optInt("redeemable_quantity", 0),
                        rewardType = row.optText("reward_type") ?: "",
                        rewardValue = row.doubleAny("reward_value") ?: 0.0,
                        rewardMenuItemId = row.longAny("reward_menu_item_id"),
                        rewardMenuItemName = row.optText("reward_menu_item_name") ?: "",
                        rewardMenuItemVariationId = row.longAny("reward_menu_item_variation_id")
                    )
                )
            }
        }

        return LoyaltySummary(
            contractVersion = obj.optText("contract_version") ?: "",
            moduleEnabled = obj.optBoolean("module_enabled", false),
            programEnabled = obj.optBoolean("program_enabled", false),
            posEnabled = obj.optBoolean("pos_enabled", false),
            settingsConfigured = obj.optBoolean("settings_configured", false),
            customer = customerObj?.longAny("id", "customer_id")?.let { customerId ->
                LoyaltyCustomer(
                    id = customerId,
                    name = customerObj.optText("name", "full_name") ?: "",
                    phone = customerObj.optText("phone", "phone_number") ?: ""
                )
            },
            points = LoyaltyPointsSummary(
                enabled = pointsObj.optBoolean("enabled", false),
                availablePoints = pointsObj.optInt("available_points", 0),
                pointsValue = pointsObj.doubleAny("points_value") ?: 0.0,
                maxDiscount = pointsObj.doubleAny("max_discount") ?: 0.0,
                pointsRequired = pointsObj.optInt("points_required", 0),
                minRedeemPoints = pointsObj.optInt("min_redeem_points", 0),
                valuePerPoint = pointsObj.doubleAny("value_per_point") ?: 0.0,
                maxDiscountPercent = pointsObj.doubleAny("max_discount_percent") ?: 0.0
            ),
            stampsEnabled = stampsObj.optBoolean("enabled", false),
            stampRules = rules,
            order = parseLoyaltyOrderState(obj.optJSONObject("order")),
            customerRequired = obj.optBoolean("customer_required", false)
        )
    }

    private fun parseLoyaltyMutation(obj: JSONObject, fallbackOrderId: Long): LoyaltyMutationResult {
        return LoyaltyMutationResult(
            success = obj.optBoolean("success", false),
            operation = obj.optText("operation") ?: "",
            orderId = obj.longAny("order_id") ?: fallbackOrderId,
            pointsRedeemed = obj.optInt("points_redeemed", 0),
            discountAmount = obj.doubleAny("discount_amount") ?: 0.0,
            removed = obj.optBoolean("removed", false),
            alreadyClear = obj.optBoolean("already_clear", false),
            ruleId = obj.longAny("rule_id"),
            stampsRedeemed = obj.optInt("stamps_redeemed", 0),
            redeemedQuantity = obj.optInt("redeemed_quantity", 0),
            rewardType = obj.optText("reward_type") ?: "",
            rewardValue = obj.doubleAny("reward_value") ?: 0.0,
            rewardMenuItemId = obj.longAny("reward_menu_item_id"),
            commercial = obj.optJSONObject("commercial")?.let {
                parseCommercialSnapshot(
                    source = it,
                    orderId = fallbackOrderId,
                    fallbackTotal = it.optJSONObject("totals")?.optDouble("grand_total", 0.0) ?: 0.0
                )
            },
            loyaltyOrder = parseLoyaltyOrderState(obj.optJSONObject("loyalty_order")),
            clientOperationId = obj.optText("client_operation_id") ?: "",
            idempotentReplay = obj.optBoolean("idempotent_replay", false)
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

    suspend fun cashRegisters(
        token: String
    ): List<CashRegister> = withContext(Dispatchers.IO) {
        val json =
            request(
                "pos/cash-register/registers",
                token = token
            )

        val array =
            findArrayDeep(
                json,
                setOf("registers", "data")
            ) ?: JSONArray()

        buildList {
            for (i in 0 until array.length()) {
                val obj =
                    array.optJSONObject(i)
                        ?: continue

                val id =
                    obj.longAny(
                        "id",
                        "cash_register_id",
                        "register_id"
                    ) ?: continue

                add(
                    CashRegister(
                        id = id,
                        name =
                            obj.optText(
                                "name",
                                "register_name",
                                "title"
                            )
                                ?: "Caisse $id",
                        isActive =
                            obj.boolAny(
                                "is_active",
                                "active"
                            ) ?: true
                    )
                )
            }
        }
    }

    suspend fun cashDenominations(
        token: String
    ): List<CashDenomination> = withContext(Dispatchers.IO) {
        val json =
            request(
                "pos/cash-register/denominations",
                token = token
            )

        val array =
            findArrayDeep(
                json,
                setOf("denominations", "data")
            ) ?: JSONArray()

        buildList {
            for (i in 0 until array.length()) {
                val obj =
                    array.optJSONObject(i)
                        ?: continue

                val value =
                    obj.doubleAny(
                        "value",
                        "amount",
                        "denomination"
                    ) ?: continue

                val label =
                    obj.optText(
                        "label",
                        "name"
                    )
                        ?: String.format(
                            Locale.FRANCE,
                            "%.2f €",
                            value
                        )

                add(
                    CashDenomination(
                        label = label,
                        value = value,
                        id =
                            obj.longAny(
                                "id",
                                "denomination_id"
                            ),
                        uuid =
                            obj.optText(
                                "uuid"
                            ),
                        type =
                            obj.optText(
                                "type"
                            )
                    )
                )
            }
        }.sortedBy {
            it.value
        }
    }

    suspend fun activeCashSession(
        token: String
    ): CashSession? = withContext(Dispatchers.IO) {
        val json =
            try {
                request(
                    "pos/cash-register/sessions/active",
                    token = token
                )
            } catch (e: CookitApiException) {
                if (e.statusCode == 404) {
                    return@withContext null
                }

                throw e
            }

        val obj =
            findObjectDeep(
                json,
                setOf("session", "data")
            ) ?: json

        parseCashSession(obj)
    }

    suspend fun cashSession(
        token: String,
        sessionId: Long
    ): CashSession = withContext(Dispatchers.IO) {
        val json =
            request(
                "pos/cash-register/sessions/$sessionId",
                token = token
            )

        val obj =
            findObjectDeep(
                json,
                setOf("session", "data")
            ) ?: json

        parseCashSession(obj)
            ?: throw CookitApiException(
                200,
                "Cash session payload is incomplete."
            )
    }

    suspend fun openCashSession(
        token: String,
        registerId: Long,
        openingAmount: Double
    ): CashSession = withContext(Dispatchers.IO) {
        val body =
            JSONObject()
                .put(
                    "cash_register_id",
                    registerId
                )
                .put(
                    "opening_float",
                    openingAmount
                )
                .put(
                    "opening_amount",
                    openingAmount
                )
                .put(
                    "opening_balance",
                    openingAmount
                )

        val json =
            request(
                "pos/cash-register/sessions/open",
                method = "POST",
                token = token,
                body = body
            )

        val obj =
            findObjectDeep(
                json,
                setOf("session", "data")
            ) ?: json

        parseCashSession(obj)
            ?: throw CookitApiException(
                200,
                "Session opened but response is incomplete."
            )
    }

    suspend fun cashSessionSummary(
        token: String,
        sessionId: Long
    ): CashRegisterSummary = withContext(Dispatchers.IO) {
        val json =
            request(
                "pos/cash-register/sessions/$sessionId/summary",
                token = token
            )

        val data =
            json.optJSONObject("data")
                ?: json

        val totals =
            data.optJSONObject("totals")
                ?: JSONObject()

        CashRegisterSummary(
            totals =
                CashRegisterTotals(
                    openingFloat =
                        totals.doubleAny(
                            "opening_float",
                            "opening_amount"
                        ) ?: 0.0,
                    cashSales =
                        totals.doubleAny(
                            "cash_sales"
                        ) ?: 0.0,
                    cashIn =
                        totals.doubleAny(
                            "cash_in"
                        ) ?: 0.0,
                    cashOut =
                        totals.doubleAny(
                            "cash_out"
                        ) ?: 0.0,
                    safeDrops =
                        totals.doubleAny(
                            "safe_drops",
                            "safe_drop"
                        ) ?: 0.0,
                    refunds =
                        totals.doubleAny(
                            "refunds"
                        ) ?: 0.0,
                    runningTotal =
                        totals.doubleAny(
                            "running_total"
                        ) ?: 0.0
                ),
            expectedCash =
                totals.doubleAny(
                    "expected_cash",
                    "expected_amount"
                )
                    ?: data.doubleAny(
                        "expected_cash",
                        "expected_amount"
                    )
                    ?: 0.0,
            countedCash =
                totals.doubleAny(
                    "counted_cash"
                )
                    ?: data.doubleAny(
                        "counted_cash"
                    )
                    ?: 0.0,
            physicalCashCounted =
                totals.doubleAny(
                    "physical_cash_counted"
                )
                    ?: data.doubleAny(
                        "physical_cash_counted"
                    ),
            discrepancy =
                totals.doubleAny(
                    "discrepancy"
                )
                    ?: data.doubleAny(
                        "discrepancy"
                    )
                    ?: 0.0,
            transactionsCount =
                (
                    totals.longAny(
                        "transactions_count"
                    )
                        ?: data.longAny(
                            "transactions_count"
                        )
                )?.toInt() ?: 0
        )
    }

    suspend fun cashTransactions(
        token: String,
        sessionId: Long
    ): List<CashMovement> = withContext(Dispatchers.IO) {
        val json =
            request(
                "pos/cash-register/sessions/$sessionId/transactions",
                token = token
            )

        val array =
            findArrayDeep(
                json,
                setOf("transactions", "data")
            ) ?: JSONArray()

        buildList {
            for (i in 0 until array.length()) {
                val obj =
                    array.optJSONObject(i)
                        ?: continue

                parseCashMovement(obj)
                    ?.let {
                        add(it)
                    }
            }
        }
    }

    suspend fun cashIn(
        token: String,
        amount: Double,
        reason: String?,
        reference: String? = null
    ): CashMovement =
        recordCashMovement(
            token = token,
            endpoint = "cash-in",
            amount = amount,
            reason = reason,
            reference = reference
        )

    suspend fun cashOut(
        token: String,
        amount: Double,
        reason: String?,
        reference: String? = null
    ): CashMovement =
        recordCashMovement(
            token = token,
            endpoint = "cash-out",
            amount = amount,
            reason = reason,
            reference = reference
        )

    suspend fun safeDrop(
        token: String,
        amount: Double,
        reason: String?,
        reference: String? = null
    ): CashMovement =
        recordCashMovement(
            token = token,
            endpoint = "safe-drop",
            amount = amount,
            reason = reason,
            reference = reference
        )

    private suspend fun recordCashMovement(
        token: String,
        endpoint: String,
        amount: Double,
        reason: String?,
        reference: String?
    ): CashMovement = withContext(Dispatchers.IO) {
        val body =
            JSONObject()
                .put(
                    "amount",
                    amount
                )

        if (! reason.isNullOrBlank()) {
            body.put(
                "reason",
                reason.trim()
            )
        }

        if (! reference.isNullOrBlank()) {
            body.put(
                "reference",
                reference.trim()
            )
        }

        val json =
            request(
                "pos/cash-register/transactions/$endpoint",
                method = "POST",
                token = token,
                body = body
            )

        val obj =
            findObjectDeep(
                json,
                setOf("transaction", "data")
            ) ?: json

        parseCashMovement(obj)
            ?: throw CookitApiException(
                200,
                "Cash movement response is incomplete."
            )
    }

    suspend fun closeCashSession(
        token: String,
        sessionId: Long,
        denominationCounts: Map<Long, Int>,
        closingNote: String?
    ): CashSession = withContext(Dispatchers.IO) {
        val counts =
            JSONArray()

        denominationCounts
            .toSortedMap()
            .forEach {
                (denominationId, count) ->

                counts.put(
                    JSONObject()
                        .put(
                            "denomination_id",
                            denominationId
                        )
                        .put(
                            "count",
                            count.coerceAtLeast(0)
                        )
                )
            }

        val body =
            JSONObject()
                .put(
                    "denomination_counts",
                    counts
                )

        if (! closingNote.isNullOrBlank()) {
            body.put(
                "closing_note",
                closingNote.trim()
            )
        }

        val json =
            request(
                "pos/cash-register/sessions/$sessionId/close",
                method = "POST",
                token = token,
                body = body
            )

        val obj =
            findObjectDeep(
                json,
                setOf("session", "data")
            ) ?: json

        parseCashSession(obj)
            ?: cashSession(
                token,
                sessionId
            )
    }

    suspend fun approveCashSession(
        token: String,
        sessionId: Long
    ): CashSession =
        mutateCashClosingApproval(
            token = token,
            sessionId = sessionId,
            action = "approve"
        )

    suspend fun rejectCashSession(
        token: String,
        sessionId: Long
    ): CashSession =
        mutateCashClosingApproval(
            token = token,
            sessionId = sessionId,
            action = "reject"
        )

    private suspend fun mutateCashClosingApproval(
        token: String,
        sessionId: Long,
        action: String
    ): CashSession = withContext(Dispatchers.IO) {
        val normalizedAction =
            action
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        if (
            normalizedAction !in
            setOf(
                "approve",
                "reject"
            )
        ) {
            throw IllegalArgumentException(
                "Unsupported CashRegister manager action."
            )
        }

        val json =
            request(
                "pos/cash-register/sessions/" +
                    "$sessionId/$normalizedAction",
                method = "POST",
                token = token,
                body = JSONObject()
            )

        val obj =
            findObjectDeep(
                json,
                setOf(
                    "session",
                    "data"
                )
            ) ?: json

        parseCashSession(
            obj
        ) ?: throw CookitApiException(
            200,
            "Cash-register manager response is incomplete."
        )
    }

    suspend fun cashRegisterReport(
        token: String,
        sessionId: Long,
        reportType: String
    ): CashRegisterReport =
        withContext(
            Dispatchers.IO
        ) {
            val normalizedType =
                reportType
                    .trim()
                    .lowercase(
                        Locale.ROOT
                    )

            if (
                normalizedType !in
                setOf(
                    "x",
                    "z"
                )
            ) {
                throw IllegalArgumentException(
                    "Cash-register report type must be X or Z."
                )
            }

            val json =
                request(
                    "pos/cash-register/sessions/" +
                        "$sessionId/reports/$normalizedType",
                    token = token
                )

            val obj =
                json.optJSONObject(
                    "data"
                ) ?: json

            parseCashRegisterReport(
                obj
            ) ?: throw CookitApiException(
                200,
                "Cash-register report payload is incomplete."
            )
        }

    private fun parseCashRegisterReport(
        obj: JSONObject
    ): CashRegisterReport? {
        val reportType =
            obj.optText(
                "report_type"
            )
                ?.trim()
                ?.lowercase(
                    Locale.ROOT
                )
                ?: return null

        if (
            reportType !in
            setOf(
                "x",
                "z"
            )
        ) {
            return null
        }

        val session =
            obj.optJSONObject(
                "session"
            ) ?: return null

        val sessionId =
            session.longAny(
                "id",
                "session_id"
            ) ?: return null

        val register =
            session.optJSONObject(
                "register"
            )

        val cashier =
            session.optJSONObject(
                "cashier"
            )

        val paymentMethodTotals =
            linkedMapOf<String, Double>()

        obj.optJSONObject(
            "payment_method_totals"
        )
            ?.let {
                methods ->

                val keys =
                    methods.keys()

                while (
                    keys.hasNext()
                ) {
                    val key =
                        keys.next()

                    val amount =
                        methods.optDouble(
                            key,
                            Double.NaN
                        )

                    if (
                        ! amount.isNaN()
                    ) {
                        paymentMethodTotals[
                            key
                        ] = amount
                    }
                }
            }

        val denominations =
            mutableListOf<CashReportDenomination>()

        val denominationArray =
            obj.optJSONArray(
                "denominations"
            ) ?: JSONArray()

        for (
            index in 0 until denominationArray.length()
        ) {
            val denomination =
                denominationArray.optJSONObject(
                    index
                ) ?: continue

            val value =
                denomination.doubleAny(
                    "value"
                ) ?: 0.0

            val count =
                denomination.longAny(
                    "count"
                )
                    ?.toInt()
                    ?: 0

            val subtotal =
                denomination.doubleAny(
                    "subtotal"
                )
                    ?: (
                        value *
                            count.toDouble()
                    )

            val label =
                denomination.optText(
                    "label"
                )
                    ?: String.format(
                        Locale.FRANCE,
                        "%.2f €",
                        value
                    )

            denominations +=
                CashReportDenomination(
                    label = label,
                    value = value,
                    count = count,
                    subtotal = subtotal
                )
        }

        return CashRegisterReport(
            reportType = reportType,
            sessionId = sessionId,
            sessionStatus =
                session.optText(
                    "status"
                ) ?: "",
            registerName =
                register?.optText(
                    "name"
                ),
            cashierName =
                cashier?.optText(
                    "name"
                ),
            openedAt =
                session.optText(
                    "opened_at"
                ),
            closedAt =
                session.optText(
                    "closed_at"
                ),
            generatedAt =
                obj.optText(
                    "generated_at"
                ),
            openingFloat =
                obj.doubleAny(
                    "opening_float"
                ) ?: 0.0,
            cashSales =
                obj.doubleAny(
                    "cash_sales"
                ) ?: 0.0,
            paymentMethodTotals =
                paymentMethodTotals,
            totalPayments =
                obj.doubleAny(
                    "total_payments"
                ) ?: 0.0,
            changeGiven =
                obj.doubleAny(
                    "change_given"
                ) ?: 0.0,
            cashIn =
                obj.doubleAny(
                    "cash_in"
                ) ?: 0.0,
            cashOut =
                obj.doubleAny(
                    "cash_out"
                ) ?: 0.0,
            safeDrops =
                obj.doubleAny(
                    "safe_drops"
                ) ?: 0.0,
            refunds =
                obj.doubleAny(
                    "refunds"
                ) ?: 0.0,
            expectedCash =
                obj.doubleAny(
                    "expected_cash"
                ) ?: 0.0,
            countedCash =
                obj.doubleAny(
                    "counted_cash"
                ),
            physicalCashCounted =
                obj.doubleAny(
                    "physical_cash_counted"
                ),
            discrepancy =
                obj.doubleAny(
                    "discrepancy"
                ),
            denominations =
                denominations
        )
    }

    private fun parseCashSession(
        obj: JSONObject
    ): CashSession? {
        val id =
            obj.longAny(
                "id",
                "session_id"
            ) ?: return null

        val register =
            obj.optJSONObject(
                "cash_register"
            )
                ?: obj.optJSONObject(
                    "register"
                )

        return CashSession(
            id = id,
            registerId =
                obj.longAny(
                    "cash_register_id",
                    "register_id"
                )
                    ?: register?.longAny(
                        "id"
                    ),
            status =
                obj.optText(
                    "status"
                )
                    ?: "open",
            openingAmount =
                obj.doubleAny(
                    "opening_float",
                    "opening_amount",
                    "opening_balance",
                    "opening_cash"
                ),
            expectedAmount =
                obj.doubleAny(
                    "expected_cash",
                    "expected_amount",
                    "expected_balance"
                ),
            registerName =
                register?.optText(
                    "name"
                ),
            openedAt =
                obj.optText(
                    "opened_at"
                ),
            closedAt =
                obj.optText(
                    "closed_at"
                ),
            physicalCashCounted =
                obj.doubleAny(
                    "physical_cash_counted"
                ),
            countedCash =
                obj.doubleAny(
                    "counted_cash"
                ),
            discrepancy =
                obj.doubleAny(
                    "discrepancy"
                ),
            closingAttemptId =
                obj.optText(
                    "closing_attempt_id"
                ),
            transactionsCount =
                obj.longAny(
                    "transactions_count"
                )?.toInt() ?: 0
        )
    }

    private fun parseCashMovement(
        obj: JSONObject
    ): CashMovement? {
        val id =
            obj.longAny(
                "id",
                "transaction_id"
            ) ?: return null

        return CashMovement(
            id = id,
            type =
                obj.optText(
                    "type"
                )
                    ?: "unknown",
            amount =
                obj.doubleAny(
                    "amount"
                ) ?: 0.0,
            runningAmount =
                obj.doubleAny(
                    "running_amount"
                ),
            reason =
                obj.optText(
                    "reason"
                ),
            reference =
                obj.optText(
                    "reference"
                ),
            happenedAt =
                obj.optText(
                    "happened_at"
                ),
            createdBy =
                obj.longAny(
                    "created_by"
                )
        )
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
    ): JSONObject = requestAbsolute(baseUrl + path.trimStart('/'), method, token, body)

    private fun requestAbsolute(
        absoluteUrl: String,
        method: String = "GET",
        token: String? = null,
        body: JSONObject? = null
    ): JSONObject {
        val connection = (URL(absoluteUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", languageCode)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")

            if (
                nativeDeviceId.isNotBlank()
            ) {
                setRequestProperty(
                    "X-COOKIT-DEVICE-ID",
                    nativeDeviceId.trim()
                )
            }

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
