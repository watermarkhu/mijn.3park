package com.watermarkhu.mijn3park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the exact alarms [SessionScheduler] registers: planned sessions
 * starting/ending and the recurring reminder ticks. Thin wrapper so all the
 * logic (notification posting and re-scheduling) stays in one testable place.
 */
class SessionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val plate = intent.getStringExtra(EXTRA_PLATE).orEmpty()
        when (intent.action) {
            ACTION_SESSION_STARTED ->
                SessionScheduler.onSessionStarted(context, plate, intent.getLongExtra(EXTRA_START, 0L))
            ACTION_SESSION_ENDED ->
                SessionScheduler.onSessionEnded(context, plate)
            ACTION_REMINDER ->
                SessionScheduler.onReminder(context)
        }
    }

    companion object {
        const val ACTION_SESSION_STARTED = "com.watermarkhu.mijn3park.action.SESSION_STARTED"
        const val ACTION_SESSION_ENDED = "com.watermarkhu.mijn3park.action.SESSION_ENDED"
        const val ACTION_REMINDER = "com.watermarkhu.mijn3park.action.SESSION_REMINDER"

        const val EXTRA_PLATE = "plate"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
    }
}
