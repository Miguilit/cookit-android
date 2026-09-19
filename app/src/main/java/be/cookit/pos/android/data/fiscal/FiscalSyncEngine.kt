package be.cookit.pos.android.data.fiscal

import be.cookit.pos.android.data.CookitApiException
import be.cookit.pos.android.domain.FiscalRuntimeIdentity
import be.cookit.pos.android.domain.RemoteOrderDraft

class FiscalSyncEngine(
    private val outbox: FiscalOutboxRepository,
    private val cloud: FiscalCloudClient
) {
    data class SyncResult(
        val attempted: Int,
        val queued: Int,
        val blockedProfileOff: Int,
        val failed: Int
    )

    /**
     * Reconcile prepared two-phase records after an app/process crash. A record is activated only
     * when Cookit confirms the order has reached a terminal settlement state.
     */
    suspend fun reconcilePrepared(
        identity: FiscalRuntimeIdentity,
        loadOrder: suspend (Long) -> RemoteOrderDraft
    ): Int {
        var activated = 0
        outbox.prepared(identity).forEach { entity ->
            runCatching { loadOrder(entity.orderId) }
                .getOrNull()
                ?.takeIf { isTerminalSettlement(it.settlementStatus) }
                ?.let {
                    outbox.activate(identity, entity.orderId)
                    activated++
                }
        }
        return activated
    }

    suspend fun sync(token: String, identity: FiscalRuntimeIdentity): SyncResult {
        var attempted = 0
        var queued = 0
        var profileOff = 0
        var failed = 0

        outbox.due(identity).forEach { entity ->
            attempted++
            try {
                val result = cloud.queueOrder(token, entity)
                outbox.markCloudQueued(entity, result.publicId)
                queued++
            } catch (error: CookitApiException) {
                val disabled = error.statusCode == 422 && isProfileDisabled(error)
                outbox.recordRetry(entity, error.message ?: "Fiscal cloud queue failed", profileOff = disabled)
                if (disabled) profileOff++ else failed++
            } catch (error: Throwable) {
                outbox.recordRetry(entity, error.message ?: "Fiscal cloud queue failed")
                failed++
            }
        }

        return SyncResult(attempted, queued, profileOff, failed)
    }

    private fun isProfileDisabled(error: CookitApiException): Boolean {
        val text = (error.message.orEmpty() + " " + error.responseBody).lowercase()
        return "profile_not_enabled" in text ||
            "profile not enabled" in text ||
            "profil fiscal" in text ||
            "fiscal profile" in text
    }

    private fun isTerminalSettlement(status: String?): Boolean = status
        ?.lowercase()
        ?.let { it in setOf("paid", "completed", "settled", "refunded", "cancelled", "canceled") }
        ?: false
}
