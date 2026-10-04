package com.watermarkhu.mijn3park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager drops every alarm on reboot. Re-register the planned
 * start/end alarms and the reminder chain from persisted state so they come
 * back without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SessionScheduler.rescheduleAll(context)
        }
    }
}
