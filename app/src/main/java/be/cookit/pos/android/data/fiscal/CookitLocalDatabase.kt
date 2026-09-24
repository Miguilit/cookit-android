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
        FiscalOutboxEntity::class,
        FiscalAgentOutcomeEntity::class,
        FiscalAgentDiagnosticEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class CookitLocalDatabase : RoomDatabase() {
    abstract fun fiscalRuntimeDao(): FiscalRuntimeDao
    abstract fun fiscalOutboxDao(): FiscalOutboxDao
    abstract fun fiscalAgentOutcomeDao(): FiscalAgentOutcomeDao
    abstract fun fiscalAgentDiagnosticDao(): FiscalAgentDiagnosticDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fiscal_agent_outcomes` (
                        `transaction_id` INTEGER NOT NULL,
                        `public_id` TEXT NOT NULL,
                        `idempotency_key` TEXT NOT NULL,
                        `snapshot_hash` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `receipt_number` TEXT NOT NULL,
                        `signature` TEXT,
                        `verification_code` TEXT,
                        `provider_reference` TEXT,
                        `raw_response_json` TEXT NOT NULL,
                        `provider_duplicate` INTEGER NOT NULL DEFAULT 0,
                        `state` TEXT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `updated_at_epoch_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`transaction_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fiscal_agent_outcomes_public_id` ON `fiscal_agent_outcomes` (`public_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fiscal_agent_outcomes_idempotency_key` ON `fiscal_agent_outcomes` (`idempotency_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_agent_outcomes_state_updated_at_epoch_ms` ON `fiscal_agent_outcomes` (`state`, `updated_at_epoch_ms`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fiscal_agent_diagnostics` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `health` TEXT NOT NULL,
                        `event_type` TEXT NOT NULL,
                        `job_id` INTEGER,
                        `job_phase` TEXT,
                        `provider` TEXT,
                        `runtime_id` TEXT,
                        `connectivity` TEXT NOT NULL,
                        `message` TEXT,
                        `error_class` TEXT,
                        `retry_count` INTEGER NOT NULL DEFAULT 0,
                        `pending_outcomes` INTEGER NOT NULL DEFAULT 0,
                        `watchdog_count` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_agent_diagnostics_created_at_epoch_ms` ON `fiscal_agent_diagnostics` (`created_at_epoch_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_agent_diagnostics_health_created_at_epoch_ms` ON `fiscal_agent_diagnostics` (`health`, `created_at_epoch_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_agent_diagnostics_event_type_created_at_epoch_ms` ON `fiscal_agent_diagnostics` (`event_type`, `created_at_epoch_ms`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_fiscal_agent_diagnostics_job_id_created_at_epoch_ms` ON `fiscal_agent_diagnostics` (`job_id`, `created_at_epoch_ms`)")
            }
        }


        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `fiscal_runtime_identity`
                    ADD COLUMN `device_id`
                    TEXT NOT NULL DEFAULT ''
                    """.trimIndent()
                )
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5
                )
                .build()
                .also { instance = it }
        }
    }
}
