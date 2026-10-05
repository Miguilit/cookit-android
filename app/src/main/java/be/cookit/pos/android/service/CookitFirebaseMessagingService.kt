package be.cookit.pos.android.service

import be.cookit.pos.android.data.CookitHttpClient
import be.cookit.pos.android.data.PushTokenStore
import be.cookit.pos.android.data.SessionStore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CookitFirebaseMessagingService :
    FirebaseMessagingService() {

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val store = PushTokenStore(this)
        store.saveToken(token)

        /*
         * Best-effort rotation update.
         * Authenticated bootstrap retries this registration later
         * if Android terminates this short-lived service.
         */
        val bearerToken =
            SessionStore(this).token()
                ?: return

        val branchId =
            store.branchId()
                ?: return

        val deviceId =
            store.deviceId()
                ?: return

        serviceScope.launch {
            runCatching {
                CookitHttpClient()
                    .apply {
                        bindNativeDeviceId(deviceId)
                    }
                    .registerNotificationToken(
                        bearerToken = bearerToken,
                        pushToken = token,
                        branchId = branchId,
                        deviceId = deviceId
                    )
            }
        }
    }

    override fun onMessageReceived(
        message: RemoteMessage
    ) {
        super.onMessageReceived(message)

        /*
         * Foreground:
         * N1D/N1E polling remains authoritative for bip,
         * banner and bell, avoiding duplicate alerts.
         *
         * Background / killed:
         * notification + data payload is displayed by Android
         * through the cookit_operational channel.
         */
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
