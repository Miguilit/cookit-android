package be.cookit.pos.android.data.fiscal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FiscalOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FiscalOutboxEntity): Long

    @Query("SELECT * FROM fiscal_outbox WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun byIdempotencyKey(idempotencyKey: String): FiscalOutboxEntity?

    @Query(
        """
        SELECT * FROM fiscal_outbox
        WHERE order_id = :orderId
          AND restaurant_id = :restaurantId
          AND branch_id = :branchId
        ORDER BY local_db_id DESC LIMIT 1
        """
    )
    suspend fun latestForOrder(orderId: Long, restaurantId: Long, branchId: Long): FiscalOutboxEntity?

    @Query(
        """
        SELECT * FROM fiscal_outbox
        WHERE restaurant_id = :restaurantId
          AND branch_id = :branchId
        ORDER BY local_db_id DESC
        LIMIT 1
        """
    )
    suspend fun latestForBranch(restaurantId: Long, branchId: Long): FiscalOutboxEntity?

    @Query(
        """
        SELECT * FROM fiscal_outbox
        WHERE status = 'prepared'
          AND restaurant_id = :restaurantId
          AND branch_id = :branchId
        ORDER BY created_at_epoch_ms ASC
        LIMIT :limit
        """
    )
    suspend fun prepared(restaurantId: Long, branchId: Long, limit: Int): List<FiscalOutboxEntity>

    @Query(
        """
        SELECT * FROM fiscal_outbox
        WHERE status IN ('pending', 'retry', 'blocked_profile_off')
          AND restaurant_id = :restaurantId
          AND branch_id = :branchId
          AND next_attempt_at_epoch_ms <= :nowEpochMs
        ORDER BY created_at_epoch_ms ASC
        LIMIT :limit
        """
    )
    suspend fun dueForCloud(restaurantId: Long, branchId: Long, nowEpochMs: Long, limit: Int): List<FiscalOutboxEntity>

    @Query(
        """
        UPDATE fiscal_outbox
        SET status = 'pending',
            activated_at_epoch_ms = COALESCE(activated_at_epoch_ms, :nowEpochMs),
            next_attempt_at_epoch_ms = :nowEpochMs,
            last_error = NULL
        WHERE local_db_id = :localDbId
        """
    )
    suspend fun activate(localDbId: Long, nowEpochMs: Long)

    @Query(
        """
        UPDATE fiscal_outbox
        SET attempts = attempts + 1,
            last_attempt_at_epoch_ms = :nowEpochMs,
            status = :status,
            next_attempt_at_epoch_ms = :nextAttemptAtEpochMs,
            last_error = :error
        WHERE local_db_id = :localDbId
        """
    )
    suspend fun recordAttempt(
        localDbId: Long,
        nowEpochMs: Long,
        status: String,
        nextAttemptAtEpochMs: Long,
        error: String?
    )

    @Query(
        """
        UPDATE fiscal_outbox
        SET status = 'cloud_queued',
            cloud_transaction_id = :cloudTransactionId,
            cloud_synced_at_epoch_ms = :nowEpochMs,
            next_attempt_at_epoch_ms = 9223372036854775807,
            last_error = NULL
        WHERE local_db_id = :localDbId
        """
    )
    suspend fun markCloudQueued(localDbId: Long, cloudTransactionId: String?, nowEpochMs: Long)

    @Query(
        """
        UPDATE fiscal_outbox
        SET status = 'fdm_submitted',
            fdm_submitted_at_epoch_ms = :nowEpochMs,
            last_error = NULL
        WHERE local_db_id = :localDbId
        """
    )
    suspend fun markFdmSubmitted(localDbId: Long, nowEpochMs: Long)

    @Query(
        """
        UPDATE fiscal_outbox
        SET status = 'fiscalized',
            fiscalized_at_epoch_ms = :nowEpochMs,
            last_error = NULL
        WHERE local_db_id = :localDbId
        """
    )
    suspend fun markFiscalized(localDbId: Long, nowEpochMs: Long)

    @Query("SELECT COUNT(*) FROM fiscal_outbox WHERE restaurant_id = :restaurantId AND branch_id = :branchId AND status = 'prepared'")
    suspend fun preparedCount(restaurantId: Long, branchId: Long): Int

    @Query("SELECT COUNT(*) FROM fiscal_outbox WHERE restaurant_id = :restaurantId AND branch_id = :branchId AND status IN ('pending', 'retry', 'blocked_profile_off')")
    suspend fun pendingCount(restaurantId: Long, branchId: Long): Int

    @Query("SELECT COUNT(*) FROM fiscal_outbox WHERE restaurant_id = :restaurantId AND branch_id = :branchId AND status = 'cloud_queued'")
    suspend fun cloudQueuedCount(restaurantId: Long, branchId: Long): Int

    @Query("SELECT COUNT(*) FROM fiscal_outbox WHERE restaurant_id = :restaurantId AND branch_id = :branchId AND status = 'fiscalized'")
    suspend fun fiscalizedCount(restaurantId: Long, branchId: Long): Int

    @Query("SELECT last_error FROM fiscal_outbox WHERE restaurant_id = :restaurantId AND branch_id = :branchId AND last_error IS NOT NULL ORDER BY last_attempt_at_epoch_ms DESC LIMIT 1")
    suspend fun latestError(restaurantId: Long, branchId: Long): String?
}
