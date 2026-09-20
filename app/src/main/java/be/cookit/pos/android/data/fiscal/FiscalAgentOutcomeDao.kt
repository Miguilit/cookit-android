package be.cookit.pos.android.data.fiscal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FiscalAgentOutcomeDao {
    @Query("SELECT * FROM fiscal_agent_outcomes WHERE transaction_id = :transactionId LIMIT 1")
    suspend fun byTransactionId(transactionId: Long): FiscalAgentOutcomeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FiscalAgentOutcomeEntity): Long

    @Query(
        """
        UPDATE fiscal_agent_outcomes
        SET state = :state,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE transaction_id = :transactionId
        """
    )
    suspend fun updateState(transactionId: Long, state: String, updatedAtEpochMs: Long)

    @Query("SELECT COUNT(*) FROM fiscal_agent_outcomes WHERE state != 'cloud_acked'")
    suspend fun pendingCount(): Int
}
