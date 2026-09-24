package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FiscalRuntimeRepository(
    private val dao: FiscalRuntimeDao
) {
    private val identityMutex = Mutex()

    suspend fun ensureIdentity(): FiscalRuntimeIdentity =
        identityMutex.withLock {
            val existing = dao.identity()

            if (existing != null) {
                return@withLock ensureDeviceId(existing).toDomain()
            }

            val now = System.currentTimeMillis()
            val candidate = FiscalRuntimeIdentityEntity(
                runtimeId = permanentId("andrt"),
                terminalId = permanentId("term"),
                deviceId = permanentId("adev"),
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )

            dao.insertIdentity(candidate)

            val persisted = dao.identity()
                ?: error("Unable to persist Cookit fiscal runtime identity")

            ensureDeviceId(persisted).toDomain()
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
            restaurantName =
                restaurantName?.takeIf { it.isNotBlank() },
            branchName =
                branchName?.takeIf { it.isNotBlank() },
            updatedAtEpochMs = System.currentTimeMillis()
        )

        return identityMutex.withLock {
            val persisted = dao.identity()
                ?: error(
                    "Cookit fiscal runtime identity disappeared after scope binding"
                )

            ensureDeviceId(persisted).toDomain()
        }
    }

    suspend fun currentIdentity(): FiscalRuntimeIdentity? =
        identityMutex.withLock {
            val persisted =
                dao.identity()
                    ?: return@withLock null

            ensureDeviceId(persisted).toDomain()
        }

    private suspend fun ensureDeviceId(
        identity: FiscalRuntimeIdentityEntity
    ): FiscalRuntimeIdentityEntity {
        if (identity.deviceId.isNotBlank()) {
            return identity
        }

        dao.bindDeviceIdIfMissing(
            deviceId = permanentId("adev"),
            updatedAtEpochMs = System.currentTimeMillis()
        )

        return dao.identity()
            ?: error(
                "Cookit fiscal runtime identity disappeared during device enrollment"
            )
    }

    private fun permanentId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").lowercase()}"

    private fun FiscalRuntimeIdentityEntity.toDomain() =
        FiscalRuntimeIdentity(
            runtimeId = runtimeId,
            terminalId = terminalId,
            deviceId = deviceId,
            createdAtEpochMs = createdAtEpochMs,
            restaurantId = boundRestaurantId,
            branchId = boundBranchId,
            restaurantName = boundRestaurantName,
            branchName = boundBranchName
        )
}
