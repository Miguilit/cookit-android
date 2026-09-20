package be.cookit.pos.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeStateStore

/**
 * Restarts the device-level Fiscal Agent after a normal device boot or an in-place APK update.
 *
 * Auto is an explicit operator/device preference. If it was OFF before reboot, this receiver does
 * nothing. Credentials remain in Android Keystore and runtime identity remains in the local DB.
 */
class FiscalAgentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext
        val stateStore = FiscalAgentRuntimeStateStore(app)
        if (!stateStore.load().autoEnabled) {
            stateStore.setServiceStatus(
                running = false,
                busy = false,
                message = "boot_auto_disabled"
            )
            return
        }

        runCatching {
            FiscalAgentServiceController.resumeIfEnabled(app)
        }.onFailure { error ->
            stateStore.setServiceStatus(
                running = false,
                busy = false,
                message = "boot_resume_failed:${error.message.orEmpty().take(160)}"
            )
        }
    }
}
