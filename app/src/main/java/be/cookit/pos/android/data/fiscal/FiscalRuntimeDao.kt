package be.cookit.pos.android.data.fiscal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FiscalRuntimeDao {
    @Query("SELECT * FROM fiscal_runtime_identity WHERE singleton_id = 1 LIMIT 1")
    suspend fun identity(): FiscalRuntimeIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentity(identity: FiscalRuntimeIdentityEntity): Long

    @Query(
        """
        UPDATE fiscal_runtime_identity
        SET device_id = :deviceId,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE singleton_id = 1
          AND TRIM(device_id) = ''
        """
    )
    suspend fun bindDeviceIdIfMissing(
        deviceId: String,
        updatedAtEpochMs: Long
    ): Int

    @Query(
        """
        UPDATE fiscal_runtime_identity
        SET bound_restaurant_id = :restaurantId,
            bound_branch_id = :branchId,
            bound_restaurant_name = :restaurantName,
            bound_branch_name = :branchName,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE singleton_id = 1
        """
    )
    suspend fun bindScope(
        restaurantId: Long?,
        branchId: Long?,
        restaurantName: String?,
        branchName: String?,
        updatedAtEpochMs: Long
    )
}
