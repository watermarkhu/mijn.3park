package com.watermarkhu.mijn3park

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Central definition of the app's notification channels. [ensure] is
 * idempotent and called from [App] on startup and defensively before every
 * session-event / reminder notification, so the channels exist even when the
 * parking service has never run.
 */
object NotificationChannels {

    /** Ongoing foreground-service notification while parking is active. */
    const val ACTIVE = "parking_active"

    /** One-shot "session started" / "session ended" events. */
    const val EVENTS = "session_events"

    /** Recurring "still parking" reminders. */
    const val REMINDERS = "session_reminders"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ACTIVE,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                EVENTS,
                context.getString(R.string.session_events_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.session_events_channel_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                REMINDERS,
                context.getString(R.string.session_reminders_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.session_reminders_channel_description)
            },
        )
    }
}
