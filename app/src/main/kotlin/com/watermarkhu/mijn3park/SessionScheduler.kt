package com.watermarkhu.mijn3park

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Pure reminder-chain arithmetic, kept free of Android types so it can be
 * unit-tested. All times are epoch milliseconds.
 */
object ReminderMath {

    /** Off, 30 min, 1, 2, 4, 8, 16 and 24 hours. */
    val INTERVAL_OPTIONS = listOf(0, 30, 60, 120, 240, 480, 960, 1440)

    /**
     * Time of the next "still parking" reminder, or `null` when there should be
     * none: reminders are off, or the next tick would land on/after the session
     * end. [endAt] `0` means the session is open-ended.
     */
    fun nextReminderAt(now: Long, intervalMinutes: Int, endAt: Long): Long? {
        if (intervalMinutes <= 0) return null
        val next = now + intervalMinutes * 60_000L
        if (endAt > 0L && next >= endAt) return null
        return next
    }
}

/**
 * Owns every notification alarm the app schedules: "session started" /
 * "session ended" for planned sessions, and the recurring "still parking"
 * reminder chain. State is derived from [Prefs] so it survives process death
 * and (with [BootReceiver]) a reboot.
 */
object SessionScheduler {

    private const val EVENTS_NOTIFICATION_ID = 10
    private const val REMINDER_NOTIFICATION_ID = 11

    /** One fixed slot for the single reminder chain. */
    private const val REMINDER_REQUEST_CODE = Int.MAX_VALUE

    // --- Public API ---

    /**
     * Persist the freshly merged planned sessions and reschedule all alarms.
     * Sessions that already started but have not ended are carried over so the
     * server-side auto-start does not drop their end (and reminder) alarms.
     */
    fun onPlannedSessionsUpdated(context: Context, merged: List<PlannedSession>) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        val previous = prefs.plannedSessions
        cancelPlannedAlarms(app, previous)
        val carryOver = previous.filter { it.startAt <= now && it.endAt > now }
        val sessions = (merged + carryOver).distinct()
        prefs.plannedSessions = sessions
        schedulePlannedAlarms(app, sessions, now)
        scheduleReminders(app, prefs)
    }

    /** Re-register every alarm from persisted state (e.g. after a reboot). */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        val sessions = prefs.plannedSessions.filter { it.endAt > now }
        prefs.plannedSessions = sessions
        schedulePlannedAlarms(app, sessions, now)
        scheduleReminders(app, prefs)
    }

    fun setReminderInterval(context: Context, minutes: Int) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        prefs.reminderIntervalMinutes = minutes
        scheduleReminders(app, prefs)
    }

    /** Cancel every scheduled alarm and forget the persisted sessions. */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        cancelPlannedAlarms(app, prefs.plannedSessions)
        cancelReminderAlarm(app)
        prefs.plannedSessions = emptyList()
    }

    /** Re-evaluate reminders after a session starts or stops. */
    fun onActiveSessionChanged(context: Context) {
        val app = context.applicationContext
        scheduleReminders(app, Prefs(app))
    }

    // --- Alarm entry points (called by SessionAlarmReceiver / ParkingService) ---

    fun onSessionStarted(context: Context, plate: String, startAt: Long) {
        val app = context.applicationContext
        notifySessionStarted(app, plate, startAt)
        scheduleReminders(app, Prefs(app))
    }

    fun onSessionEnded(context: Context, plate: String) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        cancelReminderAlarm(app)
        postSessionEvent(
            app,
            title = app.getString(R.string.notification_parking_ended),
            text = app.getString(R.string.notification_session_ended, plate),
        )
        scheduleReminders(app, prefs)
    }

    fun onReminder(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val now = System.currentTimeMillis()
        cancelReminderAlarm(app)
        val session = currentSession(prefs, now) ?: return
        postSessionReminder(app, session.plate)
        val next = ReminderMath.nextReminderAt(now, prefs.reminderIntervalMinutes, session.endAt) ?: return
        scheduleExactOrFallback(app, next, reminderPendingIntent(app, session))
    }

    /**
     * Post a "session started" event once per session. Both the start alarm and
     * the app's server sync can observe the same start; the persisted key
     * dedupes them.
     */
    fun notifySessionStarted(context: Context, plate: String, startAt: Long) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val key = "$plate|$startAt"
        if (prefs.lastSessionEventKey == key) return
        prefs.lastSessionEventKey = key
        postSessionEvent(
            app,
            title = app.getString(R.string.notification_title),
            text = app.getString(R.string.notification_session_started, plate),
        )
    }

    // --- Scheduling ---

    fun scheduleExactOrFallback(context: Context, triggerAtMillis: Long, intent: PendingIntent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val exact = (Build.VERSION.SDK_INT < 31) || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    private fun schedulePlannedAlarms(context: Context, sessions: List<PlannedSession>, now: Long) {
        for (session in sessions) {
            if (session.startAt > now) {
                scheduleExactOrFallback(
                    context,
                    session.startAt,
                    sessionPendingIntent(context, session, SessionAlarmReceiver.ACTION_SESSION_STARTED),
                )
            }
            if (session.endAt > now) {
                scheduleExactOrFallback(
                    context,
                    session.endAt,
                    sessionPendingIntent(context, session, SessionAlarmReceiver.ACTION_SESSION_ENDED),
                )
            }
        }
    }

    private fun cancelPlannedAlarms(context: Context, sessions: List<PlannedSession>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        for (session in sessions) {
            alarmManager.cancel(
                sessionPendingIntent(context, session, SessionAlarmReceiver.ACTION_SESSION_STARTED),
            )
            alarmManager.cancel(
                sessionPendingIntent(context, session, SessionAlarmReceiver.ACTION_SESSION_ENDED),
            )
        }
    }

    private fun scheduleReminders(context: Context, prefs: Prefs) {
        cancelReminderAlarm(context)
        val now = System.currentTimeMillis()
        val session = currentSession(prefs, now) ?: return
        val next = ReminderMath.nextReminderAt(now, prefs.reminderIntervalMinutes, session.endAt) ?: return
        scheduleExactOrFallback(context, next, reminderPendingIntent(context, session))
    }

    private fun cancelReminderAlarm(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(reminderPendingIntent(context, PlannedSession("", 0L, 0L)))
    }

    /**
     * The session the reminder chain belongs to: the running manual session, or
     * a planned session whose window currently contains [now]. A running
     * session whose planned end already passed is treated as over (the local
     * state is stale until the next server sync).
     */
    private fun currentSession(prefs: Prefs, now: Long): PlannedSession? {
        if (prefs.isParking) {
            if (prefs.activeEndAt > 0L) {
                return PlannedSession(prefs.activePlate, prefs.activeSince, prefs.activeEndAt)
            }
            val planned = prefs.plannedSessions
                .filter { it.plate == prefs.activePlate }
                .maxByOrNull { it.endAt }
            if (planned != null) {
                if (planned.endAt <= now) return null
                return PlannedSession(prefs.activePlate, prefs.activeSince, planned.endAt)
            }
            return PlannedSession(prefs.activePlate, prefs.activeSince, 0L)
        }
        return prefs.plannedSessions.firstOrNull { it.startAt <= now && now < it.endAt }
    }

    private fun sessionPendingIntent(
        context: Context,
        session: PlannedSession,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java)
            .setAction(action)
            .putExtra(SessionAlarmReceiver.EXTRA_PLATE, session.plate)
            .putExtra(SessionAlarmReceiver.EXTRA_START, session.startAt)
            .putExtra(SessionAlarmReceiver.EXTRA_END, session.endAt)
        return PendingIntent.getBroadcast(
            context,
            sessionRequestCode(session, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reminderPendingIntent(context: Context, session: PlannedSession): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java)
            .setAction(SessionAlarmReceiver.ACTION_REMINDER)
            .putExtra(SessionAlarmReceiver.EXTRA_PLATE, session.plate)
            .putExtra(SessionAlarmReceiver.EXTRA_END, session.endAt)
        return PendingIntent.getBroadcast(
            context,
            REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable per session and action, so recomputing cancels the same alarm. */
    private fun sessionRequestCode(session: PlannedSession, action: String): Int =
        ("$action|${session.plate}|${session.startAt}").hashCode() and 0x00FFFFFF

    // --- Notifications ---

    private fun postSessionEvent(context: Context, title: String, text: String) {
        post(context, NotificationChannels.EVENTS, EVENTS_NOTIFICATION_ID, title, text)
    }

    private fun postSessionReminder(context: Context, plate: String) {
        post(
            context,
            NotificationChannels.REMINDERS,
            REMINDER_NOTIFICATION_ID,
            context.getString(R.string.notification_title),
            context.getString(R.string.notification_session_reminder, plate),
        )
    }

    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!notificationsAllowed(context)) return
        NotificationChannels.ensure(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(id, notification)
    }

    /** API 33+ runtime permission; always allowed below that. */
    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
