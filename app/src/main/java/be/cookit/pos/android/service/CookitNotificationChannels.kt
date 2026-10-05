package be.cookit.pos.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

object CookitNotificationChannels {
    const val OPERATIONAL_CHANNEL_ID =
        "cookit_operational"

    fun ensureCreated(context: Context) {
        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        if (
            manager.getNotificationChannel(
                OPERATIONAL_CHANNEL_ID
            ) != null
        ) {
            return
        }

        val sound =
            RingtoneManager.getDefaultUri(
                RingtoneManager.TYPE_NOTIFICATION
            )

        val audioAttributes =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes.USAGE_NOTIFICATION_EVENT
                )
                .build()

        val channel =
            NotificationChannel(
                OPERATIONAL_CHANNEL_ID,
                "Commandes et appels Cookit",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Nouvelles commandes et appels serveur"
                enableVibration(true)
                setSound(
                    sound,
                    audioAttributes
                )
            }

        manager.createNotificationChannel(channel)
    }
}
