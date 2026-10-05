package be.cookit.pos.android.data

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class PushRegistrationManager(
    context: Context,
    private val api: CookitHttpClient
) {
    private val store =
        PushTokenStore(
            context.applicationContext
        )

    suspend fun registerIfAvailable(
        bearerToken: String,
        branchId: Long?,
        deviceId: String?
    ): Boolean {
        val resolvedBranchId =
            branchId?.takeIf { it > 0L }
                ?: return false

        val resolvedDeviceId =
            deviceId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return false

        val pushToken =
            firebaseToken()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: store.token()
                ?: return false

        store.saveToken(pushToken)

        api.registerNotificationToken(
            bearerToken = bearerToken,
            pushToken = pushToken,
            branchId = resolvedBranchId,
            deviceId = resolvedDeviceId
        )

        store.saveRegistrationContext(
            branchId = resolvedBranchId,
            deviceId = resolvedDeviceId
        )

        return true
    }

    private suspend fun firebaseToken(): String? =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                FirebaseMessaging
                    .getInstance()
                    .token
                    .addOnCompleteListener { task ->
                        if (!continuation.isActive) {
                            return@addOnCompleteListener
                        }

                        continuation.resume(
                            if (task.isSuccessful) {
                                task.result
                            } else {
                                null
                            }
                        )
                    }
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
}
