package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FiscalRuntimeRepository(
    private val dao: FiscalRuntimeDao
) {
    private val identityMutex = Mutex()

    suspend fun ensureIdentity(): FiscalRuntimeIdentity = identityMutex.withLock {
        dao.identity()?.toDomain()?.let { return@withLock it }

        val now = System.currentTimeMillis()
        val candidate = FiscalRuntimeIdentityEntity(
            runtimeId = permanentId("andrt"),
            terminalId = permanentId("term"),
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )

        // The singleton PK makes first-run creation race-safe. If another coroutine/process
        // wins the insert, read back the canonical row instead of regenerating identifiers.
        dao.insertIdentity(candidate)
        val persisted = dao.identity()
            ?: error("Unable to persist Cookit fiscal runtime identity")
        persisted.toDomain()
    }

    suspend fun bindScope(
        restaurantId: Long?,
        branchId: Long?,
        restaurantName: String?,
        branchName: String?
    ): FiscalRuntimeIdentity {
        ensureIdentity()
        dao.bindScope(
            restaurantId = restaurantId,
            branchId = branchId,
            restaurantName = restaurantName?.takeIf { it.isNotBlank() },
            branchName = branchName?.takeIf { it.isNotBlank() },
            updatedAtEpochMs = System.currentTimeMillis()
        )
        return dao.identity()?.toDomain()
            ?: error("Cookit fiscal runtime identity disappeared after scope binding")
    }

    suspend fun currentIdentity(): FiscalRuntimeIdentity? = dao.identity()?.toDomain()

    private fun permanentId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").lowercase()}"

    private fun FiscalRuntimeIdentityEntity.toDomain() = FiscalRuntimeIdentity(
        runtimeId = runtimeId,
        terminalId = terminalId,
        createdAtEpochMs = createdAtEpochMs,
        restaurantId = boundRestaurantId,
        branchId = boundBranchId,
        restaurantName = boundRestaurantName,
        branchName = boundBranchName
    )
}
