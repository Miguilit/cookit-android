package be.cookit.pos.android.data.fiscal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One immutable Cookit installation identity.
 *
 * device_id, runtime_id and terminal_id are generated once and are never
 * coupled to the logged-in user. Restaurant/branch binding is mutable.
 */
@Entity(tableName = "fiscal_runtime_identity")
data class FiscalRuntimeIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "runtime_id")
    val runtimeId: String,
    @ColumnInfo(name = "terminal_id")
    val terminalId: String,
    @ColumnInfo(name = "device_id", defaultValue = "''")
    val deviceId: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
    @ColumnInfo(name = "bound_restaurant_id")
    val boundRestaurantId: Long? = null,
    @ColumnInfo(name = "bound_branch_id")
    val boundBranchId: Long? = null,
    @ColumnInfo(name = "bound_restaurant_name")
    val boundRestaurantName: String? = null,
    @ColumnInfo(name = "bound_branch_name")
    val boundBranchName: String? = null
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
