package be.cookit.pos.android.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import be.cookit.pos.android.data.fiscal.FiscalAgentRuntimeStateStore

object FiscalAgentServiceController {
    fun start(context: Context) {
        val app = context.applicationContext
        FiscalAgentRuntimeStateStore(app).setAutoEnabled(true)
        ContextCompat.startForegroundService(
            app,
            Intent(app, FiscalAgentForegroundService::class.java)
                .setAction(FiscalAgentForegroundService.ACTION_START)
        )
    }

    fun resumeIfEnabled(context: Context) {
        val app = context.applicationContext
        if (!FiscalAgentRuntimeStateStore(app).load().autoEnabled) return
        ContextCompat.startForegroundService(
            app,
            Intent(app, FiscalAgentForegroundService::class.java)
                .setAction(FiscalAgentForegroundService.ACTION_RESUME)
        )
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        val store = FiscalAgentRuntimeStateStore(app)
        store.setAutoEnabled(false)
        app.stopService(Intent(app, FiscalAgentForegroundService::class.java))
        store.setServiceStatus(
            running = false,
            busy = false,
            message = "auto_stopped"
        )
    }
}
