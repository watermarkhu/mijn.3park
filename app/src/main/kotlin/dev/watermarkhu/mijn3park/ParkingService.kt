package dev.watermarkhu.mijn3park

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
        const val ACTION_START = "dev.watermarkhu.mijn3park.action.START"
        const val ACTION_STOP = "dev.watermarkhu.mijn3park.action.STOP"
        const val ACTION_RENEW = "dev.watermarkhu.mijn3park.action.RENEW"
        const val EXTRA_PLATE = "plate"

        const val CHANNEL_ID = "parking_active"
        const val NOTIFICATION_ID = 1

        /** Invoked on the main thread whenever parking state changes. */
        @Volatile
        var onStateChanged: (() -> Unit)? = null

        @Volatile
        var lastError: String? = null

        fun start(context: Context, plate: String) {
            val intent = Intent(context, ParkingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PLATE, normalizePlate(plate))
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
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting), plate))
                startParking(plate)
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
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_stopping), prefs.activePlate))
                stopParking()
            }
            else -> {
                // Restarted by the system: resume state if we were parking.
                if (prefs.isParking) {
                    startForeground(NOTIFICATION_ID, activeNotification())
                    scheduleMidnightRenewal()
                } else {
                    stopSelfCompletely()
                }
            }
        }
        return START_STICKY
    }

    private fun startParking(plate: String) {
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
                lastError = null
                notify(activeNotification())
                scheduleMidnightRenewal()
                onStateChanged?.invoke()
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
                notify(activeNotification())
                scheduleMidnightRenewal()
                onStateChanged?.invoke()
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                notify(
                    buildNotification(
                        getString(R.string.notification_renew_failed, lastError),
                        plate,
                    )
                )
                // Retry in 5 minutes; parking should stay on until user stops it.
                scheduleAlarm(System.currentTimeMillis() + 5 * 60_000L)
            }
        }
    }

    private fun stopParking() {
        val plate = prefs.activePlate
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                if (plate.isNotBlank()) {
                    ensureCredentials()
                    withContext(Dispatchers.IO) { api.stop(prefs.productId, plate) }
                }
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
            } finally {
                prefs.clearActiveParking()
                cancelAlarm()
                onStateChanged?.invoke()
                stopSelfCompletely()
            }
        }
    }

    private suspend fun ensureCredentials() {
        if (api.email.isBlank()) {
            api.login(prefs.email, prefs.password)
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
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
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
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 23) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, renewPendingIntent()
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, renewPendingIntent())
        }
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(renewPendingIntent())
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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun activeNotification(): Notification {
        val since = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(prefs.activeSince))
        return buildNotification(
            getString(R.string.notification_text, prefs.activePlate, since),
            prefs.activePlate,
            showStop = true,
        )
    }

    private fun buildNotification(text: String, plate: String, showStop: Boolean = false): Notification {
        val contentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), contentFlags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        currentJob?.cancel()
        super.onDestroy()
    }
}
