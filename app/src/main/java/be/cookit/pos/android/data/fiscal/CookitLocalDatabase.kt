package be.cookit.pos.android.data.fiscal

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FiscalRuntimeIdentityEntity::class,
        FiscalOutboxEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class CookitLocalDatabase : RoomDatabase() {
    abstract fun fiscalRuntimeDao(): FiscalRuntimeDao
    abstract fun fiscalOutboxDao(): FiscalOutboxDao

    companion object {
        const val DATABASE_NAME = "cookit_local_runtime.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fiscal_outbox` (
                        `local_db_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `local_event_id` TEXT NOT NULL,
                        `idempotency_key` TEXT NOT NULL,
                        `runtime_id` TEXT NOT NULL,
                        `terminal_id` TEXT NOT NULL,
                        `restaurant_id` INTEGER,
                        `branch_id` INTEGER,
                        `order_id` INTEGER NOT NULL,
                        `order_type` TEXT NOT NULL,
                        `payment_method` TEXT NOT NULL,
                        `sce_event_class` TEXT NOT NULL,
                        `sce_event_type` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `gross_total_minor` INTEGER NOT NULL,
                        `snapshot_json` TEXT NOT NULL,
                        `snapshot_hash` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `cloud_transaction_id` TEXT,
                        `attempts` INTEGER NOT NULL,
                        `next_attempt_at_epoch_ms` INTEGER NOT NULL,
                        `last_attempt_at_epoch_ms` INTEGER,
                        `last_error` TEXT,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `activated_at_epoch_ms` INTEGER,
                        `cloud_synced_at_epoch_ms` INTEGER,
                        `fdm_submitted_at_epoch_ms` INTEGER,
                        `fiscalized_at_epoch_ms` INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fiscal_outbox_local_event_id` ON `fiscal_outbox` (`local_event_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fiscal_outbox_idempotency_key` ON `fiscal_outbox` (`idempotency_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_outbox_order_id` ON `fiscal_outbox` (`order_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_outbox_status_next_attempt_at_epoch_ms` ON `fiscal_outbox` (`status`, `next_attempt_at_epoch_ms`)")
            }
        }

        @Volatile
        private var instance: CookitLocalDatabase? = null

        fun get(context: Context): CookitLocalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CookitLocalDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
