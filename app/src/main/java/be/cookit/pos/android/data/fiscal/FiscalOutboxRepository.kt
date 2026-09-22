package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.CartLine
import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import be.cookit.pos.android.domain.OrderType
import be.cookit.pos.android.domain.PosPaymentMethod
import be.cookit.pos.android.domain.RemoteOrderLine
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

data class FiscalOutboxHealth(
    val prepared: Int = 0,
    val pending: Int = 0,
    val cloudQueued: Int = 0,
    val fiscalized: Int = 0,
    val latestError: String? = null
)

class FiscalOutboxRepository(
    private val dao: FiscalOutboxDao
) {
    suspend fun prepareSale(
        identity: FiscalRuntimeIdentity,
        orderId: Long,
        orderType: OrderType,
        lines: List<CartLine>,
        amount: Double,
        paymentMethod: PosPaymentMethod,
        cashierId: Long? = null,
        cashierName: String? = null,
        categoryNamesById: Map<Long, String> = emptyMap(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): FiscalOutboxEntity = prepare(
        identity = identity,
        orderId = orderId,
        orderType = orderType,
        lines = lines.map {
            LineSnapshot(
                productId = it.product.id,
                name = it.product.name,
                quantity = it.quantity,
                unitPriceMinor = moneyMinor(it.product.price),
                lineTotalMinor = moneyMinor(it.total),
                departmentId = it.product.categoryId,
                departmentName = categoryNamesById[it.product.categoryId],
                vatRate = it.product.vatRate,
                vatLabel = it.product.vatLabel
            )
        },
        amountMinor = moneyMinor(amount),
        paymentMethod = paymentMethod.apiValue,
        cashierId = cashierId,
        cashierName = cashierName,
        nowEpochMs = nowEpochMs
    )

    suspend fun prepareRemoteSale(
        identity: FiscalRuntimeIdentity,
        orderId: Long,
        orderType: OrderType,
        lines: List<RemoteOrderLine>,
        amount: Double,
        paymentMethod: String,
        cashierId: Long? = null,
        cashierName: String? = null,
        categoryNamesById: Map<Long, String> = emptyMap(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): FiscalOutboxEntity = prepare(
        identity = identity,
        orderId = orderId,
        orderType = orderType,
        lines = lines.map {
            LineSnapshot(
                productId = it.menuItemId,
                name = it.name ?: "Article ${it.menuItemId}",
                quantity = it.quantity,
                unitPriceMinor = moneyMinor(it.price),
                lineTotalMinor = moneyMinor(it.price * it.quantity),
                departmentId = it.categoryId,
                departmentName = it.categoryId?.let(categoryNamesById::get),
                vatRate = it.vatRate,
                vatLabel = it.vatLabel
            )
        },
        amountMinor = moneyMinor(amount),
        paymentMethod = paymentMethod,
        cashierId = cashierId,
        cashierName = cashierName,
        nowEpochMs = nowEpochMs
    )

    suspend fun prepareMinimalSale(
        identity: FiscalRuntimeIdentity,
        orderId: Long,
        orderType: OrderType,
        amount: Double,
        paymentMethod: String,
        cashierId: Long? = null,
        cashierName: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): FiscalOutboxEntity = prepare(
        identity = identity,
        orderId = orderId,
        orderType = orderType,
        lines = emptyList(),
        amountMinor = moneyMinor(amount),
        paymentMethod = paymentMethod,
        cashierId = cashierId,
        cashierName = cashierName,
        nowEpochMs = nowEpochMs
    )

    suspend fun activate(
        identity: FiscalRuntimeIdentity,
        orderId: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val restaurantId = identity.restaurantId ?: return
        val branchId = identity.branchId ?: return
        dao.latestForOrder(orderId, restaurantId, branchId)?.let { dao.activate(it.localDbId, nowEpochMs) }
    }

    suspend fun latestForOrder(identity: FiscalRuntimeIdentity, orderId: Long): FiscalOutboxEntity? {
        val restaurantId = identity.restaurantId ?: return null
        val branchId = identity.branchId ?: return null
        return dao.latestForOrder(orderId, restaurantId, branchId)
    }

    suspend fun latest(identity: FiscalRuntimeIdentity): FiscalOutboxEntity? {
        val restaurantId = identity.restaurantId ?: return null
        val branchId = identity.branchId ?: return null
        return dao.latestForBranch(restaurantId, branchId)
    }

    suspend fun latestActivated(identity: FiscalRuntimeIdentity): FiscalOutboxEntity? {
        val restaurantId = identity.restaurantId ?: return null
        val branchId = identity.branchId ?: return null
        return dao.latestActivatedForBranch(restaurantId, branchId)
    }

    suspend fun prepared(identity: FiscalRuntimeIdentity, limit: Int = 20): List<FiscalOutboxEntity> {
        val restaurantId = identity.restaurantId ?: return emptyList()
        val branchId = identity.branchId ?: return emptyList()
        return dao.prepared(restaurantId, branchId, limit)
    }

    suspend fun due(
        identity: FiscalRuntimeIdentity,
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int = 10
    ): List<FiscalOutboxEntity> {
        val restaurantId = identity.restaurantId ?: return emptyList()
        val branchId = identity.branchId ?: return emptyList()
        return dao.dueForCloud(restaurantId, branchId, nowEpochMs, limit)
    }

    suspend fun recordRetry(
        entity: FiscalOutboxEntity,
        error: String,
        profileOff: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val nextAttempt = nowEpochMs + retryDelayMs(entity.attempts + 1, profileOff)
        dao.recordAttempt(
            localDbId = entity.localDbId,
            nowEpochMs = nowEpochMs,
            status = if (profileOff) FiscalOutboxEntity.STATUS_BLOCKED_PROFILE_OFF else FiscalOutboxEntity.STATUS_RETRY,
            nextAttemptAtEpochMs = nextAttempt,
            error = error.take(1000)
        )
    }

    suspend fun markCloudQueued(
        entity: FiscalOutboxEntity,
        cloudTransactionId: String?,
        nowEpochMs: Long = System.currentTimeMillis()
    ) = dao.markCloudQueued(entity.localDbId, cloudTransactionId, nowEpochMs)

    suspend fun health(identity: FiscalRuntimeIdentity): FiscalOutboxHealth {
        val restaurantId = identity.restaurantId ?: return FiscalOutboxHealth()
        val branchId = identity.branchId ?: return FiscalOutboxHealth()
        return FiscalOutboxHealth(
            prepared = dao.preparedCount(restaurantId, branchId),
            pending = dao.pendingCount(restaurantId, branchId),
            cloudQueued = dao.cloudQueuedCount(restaurantId, branchId),
            fiscalized = dao.fiscalizedCount(restaurantId, branchId),
            latestError = dao.latestError(restaurantId, branchId)
        )
    }

    private suspend fun prepare(
        identity: FiscalRuntimeIdentity,
        orderId: Long,
        orderType: OrderType,
        lines: List<LineSnapshot>,
        amountMinor: Long,
        paymentMethod: String,
        cashierId: Long?,
        cashierName: String?,
        nowEpochMs: Long
    ): FiscalOutboxEntity {
        require(orderId > 0) { "A fiscal sale requires a positive Cookit order id" }
        val restaurantId = requireNotNull(identity.restaurantId) { "Fiscal runtime is not bound to a restaurant" }
        val branchId = requireNotNull(identity.branchId) { "Fiscal runtime is not bound to a branch" }
        val idempotencyKey = "android:${identity.runtimeId}:order:$orderId:sale"
        dao.byIdempotencyKey(idempotencyKey)?.let { return it }

        val localEventId = "sale_" + FiscalCanonicalJson.sha256Hex(idempotencyKey).take(32)
        val posDateTime = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(nowEpochMs))
        val bookingDate = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

        val snapshot = linkedMapOf<String, Any?>(
            "schema" to "cookit.android.fiscal.sale.v2",
            "local_event_id" to localEventId,
            "runtime_id" to identity.runtimeId,
            "terminal_id" to identity.terminalId,
            "restaurant_id" to restaurantId,
            "branch_id" to branchId,
            "order_id" to orderId,
            "source_channel" to "android_pos",
            "event_label" to "N",
            "event_type" to "sale",
            "pos_date_time" to posDateTime,
            "booking_date" to bookingDate,
            "order_type" to orderType.name.lowercase(),
            "cashier" to linkedMapOf(
                "id" to cashierId,
                "name" to cashierName
            ),
            "payment" to linkedMapOf(
                "method" to paymentMethod,
                "amount_minor" to amountMinor,
                "currency" to "EUR"
            ),
            "lines" to lines.map { line ->
                linkedMapOf<String, Any?>(
                    "product_id" to line.productId,
                    "name" to line.name,
                    "department_id" to line.departmentId,
                    "department_name" to line.departmentName,
                    "quantity" to line.quantity,
                    "unit_price_minor" to line.unitPriceMinor,
                    "line_total_minor" to line.lineTotalMinor,
                    "vat_rate" to line.vatRate,
                    "vat_label" to line.vatLabel
                )
            },
            "totals" to linkedMapOf(
                "gross_minor" to amountMinor,
                "currency" to "EUR"
            )
        )
        val snapshotJson = FiscalCanonicalJson.encode(snapshot)
        val entity = FiscalOutboxEntity(
            localEventId = localEventId,
            idempotencyKey = idempotencyKey,
            runtimeId = identity.runtimeId,
            terminalId = identity.terminalId,
            restaurantId = restaurantId,
            branchId = branchId,
            orderId = orderId,
            orderType = orderType.name.lowercase(),
            paymentMethod = paymentMethod,
            grossTotalMinor = amountMinor,
            snapshotJson = snapshotJson,
            snapshotHash = FiscalCanonicalJson.sha256Hex(snapshotJson),
            createdAtEpochMs = nowEpochMs,
            nextAttemptAtEpochMs = Long.MAX_VALUE
        )

        dao.insert(entity)
        return dao.byIdempotencyKey(idempotencyKey)
            ?: error("Unable to persist Cookit fiscal outbox event")
    }

    private fun moneyMinor(amount: Double): Long = (amount * 100.0).roundToLong()

    private fun retryDelayMs(attempt: Int, profileOff: Boolean): Long {
        if (profileOff) return 15L * 60L * 1000L
        val seconds = when (attempt.coerceAtLeast(1)) {
            1 -> 5L
            2 -> 15L
            3 -> 60L
            4 -> 5L * 60L
            5 -> 15L * 60L
            else -> 60L * 60L
        }
        return seconds * 1000L
    }

    private data class LineSnapshot(
        val productId: Long,
        val name: String,
        val quantity: Int,
        val unitPriceMinor: Long,
        val lineTotalMinor: Long,
        val departmentId: Long?,
        val departmentName: String?,
        val vatRate: Double?,
        val vatLabel: String?
    )
}
