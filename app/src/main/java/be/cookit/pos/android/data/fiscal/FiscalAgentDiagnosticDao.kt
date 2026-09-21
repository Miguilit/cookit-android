package be.cookit.pos.android.data.fiscal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FiscalAgentDiagnosticDao {
    @Insert
    suspend fun insert(entity: FiscalAgentDiagnosticEntity): Long

    @Query("SELECT * FROM fiscal_agent_diagnostics ORDER BY created_at_epoch_ms DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<FiscalAgentDiagnosticEntity>

    @Query("SELECT * FROM fiscal_agent_diagnostics ORDER BY created_at_epoch_ms DESC, id DESC LIMIT 1")
    suspend fun latestOne(): FiscalAgentDiagnosticEntity?

    @Query("SELECT COUNT(*) FROM fiscal_agent_diagnostics")
    suspend fun count(): Int

    @Query(
        """
        DELETE FROM fiscal_agent_diagnostics
        WHERE id NOT IN (
            SELECT id FROM fiscal_agent_diagnostics
            ORDER BY created_at_epoch_ms DESC, id DESC
            LIMIT :keep
        )
        """
    )
    suspend fun pruneToLatest(keep: Int)

    @Query("DELETE FROM fiscal_agent_diagnostics")
    suspend fun clear()
}
