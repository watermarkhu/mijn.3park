package com.watermarkhu.mijn3park

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Foreground service that keeps parking active until the user stops it.
 *
 * The 2Park API only allows starting parking until 23:59:59 of the current
 * day ("today" and "future days" use different mechanics on the platform).
 * To support open-ended parking, this service schedules an exact alarm just
 * after midnight that re-issues a fresh start action for the new day.
 */
class ParkingService : Service() {

    companion object {
        const val ACTION_START = "com.watermarkhu.mijn3park.action.START"
        const val ACTION_STOP = "com.watermarkhu.mijn3park.action.STOP"
        const val ACTION_RENEW = "com.watermarkhu.mijn3park.action.RENEW"
        const val ACTION_AUTO_STOP = "com.watermarkhu.mijn3park.action.AUTO_STOP"
        const val EXTRA_PLATE = "plate"
        const val EXTRA_END_AT = "end_at"

        const val CHANNEL_ID = "parking_active"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_ENDED_ID = 2

        /** Invoked on the main thread whenever parking state changes. */
        @Volatile
        var onStateChanged: (() -> Unit)? = null

        @Volatile
        var lastError: String? = null

        fun start(context: Context, plate: String, endAt: Long = 0L) {
            val intent = Intent(context, ParkingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PLATE, normalizePlate(plate))
                .putExtra(EXTRA_END_AT, endAt)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ParkingService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private val api get() = TwoParkApi.instance
    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val plate = intent.getStringExtra(EXTRA_PLATE).orEmpty()
                val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)))
                startParking(plate, endAt)
            }
            ACTION_AUTO_STOP -> {
                if ((prefs.isParking) && (prefs.activeEndAt > 0L)) {
                    startForeground(NOTIFICATION_ID, activeNotification())
                    autoStopParking()
                } else {
                    stopSelfCompletely()
                }
            }
            ACTION_RENEW -> {
                if (prefs.isParking) {
                    startForeground(NOTIFICATION_ID, activeNotification())
                    renewParking()
                } else {
                    stopSelfCompletely()
                }
            }
            ACTION_STOP -> {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_stopping)))
                stopParking()
            }
            else -> {
                // Restarted by the system: resume state if we were parking.
                if (prefs.isParking) {
                    startForeground(NOTIFICATION_ID, activeNotification())
                    scheduleMidnightRenewal()
                    scheduleEndAlarm(prefs.activeEndAt)
                } else {
                    stopSelfCompletely()
                }
            }
        }
        return START_STICKY
    }

    private fun startParking(plate: String, endAt: Long) {
        if (plate.isBlank()) {
            stopSelfCompletely()
            return
        }
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                ensureCredentials()
                withContext(Dispatchers.IO) {
                    api.start(prefs.productId, prefs.productLocation.ifBlank { null }, plate)
                }
                prefs.activePlate = plate
                prefs.activeSince = System.currentTimeMillis()
                // Ignore stale end times that passed while starting.
                prefs.activeEndAt = endAt.takeIf { it > System.currentTimeMillis() } ?: 0L
                lastError = null
                refreshBalance()
                notify(activeNotification())
                scheduleMidnightRenewal()
                scheduleEndAlarm(prefs.activeEndAt)
                onStateChanged?.invoke()
            } catch (_: AuthFailedException) {
                handleAuthFailure()
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                prefs.clearActiveParking()
                onStateChanged?.invoke()
                stopSelfCompletely()
            }
        }
    }

    private fun renewParking() {
        val plate = prefs.activePlate
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                ensureCredentials()
                withContext(Dispatchers.IO) {
                    // The previous action expired at 23:59:59; start a new one
                    // for the new day unless one is somehow still running.
                    val active = api.findActiveMember(prefs.productId, plate)
                    if (active == null) {
                        api.start(prefs.productId, prefs.productLocation.ifBlank { null }, plate)
                    }
                }
                lastError = null
                refreshBalance()
                notify(activeNotification())
                scheduleMidnightRenewal()
                onStateChanged?.invoke()
            } catch (_: AuthFailedException) {
                handleAuthFailure()
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                notify(
                    buildNotification(
                        getString(R.string.notification_renew_failed, lastError),
                    ),
                )
                // Retry in 5 minutes; parking should stay on until user stops it.
                scheduleAlarm(System.currentTimeMillis() + (5 * 60_000L))
            }
        }
    }

    private fun stopParking() {
        val plate = prefs.activePlate
        currentJob?.cancel()
        currentJob = scope.launch {
            lastError = try {
                if (plate.isNotBlank()) {
                    ensureCredentials()
                    withContext(Dispatchers.IO) { api.stop(prefs.productId, plate) }
                }
                null
            } catch (_: AuthFailedException) {
                handleAuthFailure()
                return@launch
            } catch (e: Exception) {
                e.message ?: e.toString()
            } finally {
                prefs.clearActiveParking()
                cancelAlarm()
                cancelEndAlarm()
                onStateChanged?.invoke()
                stopSelfCompletely()
            }
        }
    }

    /**
     * Planned end time reached: stop server-side and report. Unlike a manual
     * stop this leaves a "parking ended" notification behind. Failures still
     * end the local state: the planned time has passed either way.
     */
    private fun autoStopParking() {
        val plate = prefs.activePlate
        currentJob?.cancel()
        currentJob = scope.launch {
            lastError = try {
                if (plate.isNotBlank()) {
                    ensureCredentials()
                    withContext(Dispatchers.IO) { api.stop(prefs.productId, plate) }
                }
                null
            } catch (_: AuthFailedException) {
                handleAuthFailure()
                return@launch
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            prefs.clearActiveParking()
            cancelAlarm()
            cancelEndAlarm()
            notifyEnded(endedNotification(plate))
            onStateChanged?.invoke()
            stopSelfCompletely()
        }
    }

    private suspend fun ensureCredentials() {
        if (api.email.isBlank()) {
            api.login(prefs.email, prefs.password)
        }
    }

    /**
     * Saved credentials were rejected: full wipe (same scope as manual
     * logout), cancel any renewal alarm, and leave a notification behind
     * instead of retrying with dead credentials.
     */
    private fun handleAuthFailure() {
        currentJob?.cancel()
        prefs.clearAll()
        api.logout()
        cancelAlarm()
        cancelEndAlarm()
        notify(buildNotification(getString(R.string.notification_logged_out)))
        onStateChanged?.invoke()
        stopSelfCompletely()
    }

    /** Best-effort balance refresh for display purposes; never fails the caller. */
    private suspend fun refreshBalance() {
        try {
            val balance = withContext(Dispatchers.IO) { api.getBalance(prefs.productId) }
            prefs.lastBalance = balance.formatted
        } catch (_: Exception) {
            // Keep the previous known balance.
        }
    }

    private fun stopSelfCompletely() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // --- Midnight renewal alarm ---

    private fun renewPendingIntent(): PendingIntent {
        val intent = Intent(this, ParkingService::class.java).setAction(ACTION_RENEW)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, 1, intent, flags)
        } else {
            PendingIntent.getService(this, 1, intent, flags)
        }
    }

    private fun scheduleMidnightRenewal() {
        val nextMidnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        scheduleAlarm(nextMidnight)
    }

    private fun scheduleAlarm(triggerAtMillis: Long) {
        scheduleExactOrFallback(triggerAtMillis, renewPendingIntent())
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(renewPendingIntent())
    }

    // --- Planned end-time alarm ---

    private fun endPendingIntent(): PendingIntent {
        val intent = Intent(this, ParkingService::class.java).setAction(ACTION_AUTO_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, 3, intent, flags)
        } else {
            PendingIntent.getService(this, 3, intent, flags)
        }
    }

    /** No-op when [endAtMillis] is not a future planned end. */
    private fun scheduleEndAlarm(endAtMillis: Long) {
        if (endAtMillis <= System.currentTimeMillis()) return
        scheduleExactOrFallback(endAtMillis, endPendingIntent())
    }

    private fun cancelEndAlarm() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(endPendingIntent())
    }

    /**
     * Exact alarms need SCHEDULE_EXACT_ALARM on API 31+; fall back to an
     * inexact alarm when the user did not grant it instead of crashing.
     */
    private fun scheduleExactOrFallback(triggerAtMillis: Long, intent: PendingIntent) {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val exact =
            (Build.VERSION.SDK_INT < 31) || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, intent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    // --- Notifications ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun activeNotification(): Notification {
        val since = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(prefs.activeSince))
        val balance = prefs.lastBalance
        val endAt = prefs.activeEndAt
        val text = if (balance.isNotBlank()) {
            if (endAt > 0L) {
                getString(
                    R.string.notification_text_balance_until,
                    prefs.activePlate, since, balance, formatNotifEnd(endAt),
                )
            } else {
                getString(R.string.notification_text_balance, prefs.activePlate, since, balance)
            }
        } else {
            if (endAt > 0L) {
                getString(
                    R.string.notification_text_until,
                    prefs.activePlate, since, formatNotifEnd(endAt),
                )
            } else {
                getString(R.string.notification_text, prefs.activePlate, since)
            }
        }
        return buildNotification(text, showStop = true)
    }

    /** Short end-time form: "18:00" today, "5 Mar 10:00" on another day. */
    private fun formatNotifEnd(endAtMillis: Long): String {
        val endDay = Calendar.getInstance().apply { timeInMillis = endAtMillis }
        val today = Calendar.getInstance()
        val sameDay = endDay[Calendar.YEAR] == today[Calendar.YEAR] &&
            endDay[Calendar.DAY_OF_YEAR] == today[Calendar.DAY_OF_YEAR]
        val pattern = if (sameDay) "HH:mm" else "d MMM HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(endAtMillis))
    }

    /** Final, non-ongoing notification after the planned end time is reached. */
    private fun endedNotification(plate: String): Notification {
        val contentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), contentFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(getString(R.string.notification_parking_ended))
            .setContentText(getString(R.string.notification_ended, plate))
            .setContentIntent(contentIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    private fun buildNotification(text: String, showStop: Boolean = false): Notification {
        val contentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), contentFlags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(if (prefs.activeSince > 0) prefs.activeSince else System.currentTimeMillis())

        if (showStop) {
            val stopIntent = Intent(this, ParkingService::class.java).setAction(ACTION_STOP)
            val stopPending = if (Build.VERSION.SDK_INT >= 26) {
                PendingIntent.getForegroundService(this, 2, stopIntent, contentFlags)
            } else {
                PendingIntent.getService(this, 2, stopIntent, contentFlags)
            }
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPending,
            )
        }
        return builder.build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyEnded(notification: Notification) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ENDED_ID, notification)
    }

    override fun onDestroy() {
        currentJob?.cancel()
        super.onDestroy()
    }
}
