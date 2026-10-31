package dev.pagealarm.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that re-checks the page every [INTERVAL_MS] and sounds a loud
 * alarm (alarm stream, max volume, full-screen alert) when the visible text changes.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "dev.pagealarm.app.START"
        const val ACTION_CHECK = "dev.pagealarm.app.CHECK"
        const val ACTION_STOP_MONITORING = "dev.pagealarm.app.STOP_MONITORING"
        const val ACTION_DISMISS_ALARM = "dev.pagealarm.app.DISMISS_ALARM"
        const val ACTION_TEST = "dev.pagealarm.app.TEST"

        const val INTERVAL_MS = 5 * 60 * 1000L

        private const val CHANNEL_MONITOR = "monitor"
        private const val CHANNEL_ALARM = "alarm"
        private const val ID_STATUS = 1
        private const val ID_ALARM = 2
        private const val TAG = "PageAlarm"

        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
            )
        }
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private var savedAlarmVolume = -1

    private val nm get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onDestroy() {
        stopSiren()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService(), whatever the action is.
        enterForeground()

        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALARM -> dismissAlarm()
            ACTION_TEST -> worker.execute { raiseAlarm("This is a test alarm.", test = true) }
            else -> {
                // ACTION_START, ACTION_CHECK, or a restart after the process was killed.
                if (!Store.monitoring(this)) {
                    stopEverything()
                    return START_NOT_STICKY
                }
                runCheck()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ checking

    private fun runCheck() {
        scheduleNext() // schedule first so a failure below can't end the loop
        worker.execute {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pagealarm:check")
            wl.acquire(60_000L)
            try {
                doCheck()
            } catch (t: Throwable) {
                Log.e(TAG, "check crashed", t)
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
    }

    private fun doCheck() {
        val url = Store.url(this)
        if (url.isBlank() || !Store.monitoring(this)) return

        val result: String = try {
            val lines = PageText.fetch(url)
            val base = Store.baseline(this)
            when {
                base == null -> {
                    Store.setBaseline(this, lines)
                    "Baseline saved (${lines.size} lines of text)"
                }
                base != lines -> {
                    Store.setPending(this, lines)
                    raiseAlarm(PageText.describeChange(base, lines), test = false)
                    "PAGE CHANGED"
                }
                else -> "No change"
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed", e)
            "Check failed: ${e.message ?: e.javaClass.simpleName}"
        }

        Store.setLastCheck(this, System.currentTimeMillis(), result)
        nm.notify(ID_STATUS, buildStatusNotification())
    }

    private fun scheduleNext() {
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getForegroundService(
            this, 3,
            Intent(this, MonitorService::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelTimer() {
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getForegroundService(
            this, 3,
            Intent(this, MonitorService::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
    }

    // --------------------------------------------------------------------- alarm

    @Synchronized
    private fun raiseAlarm(summary: String, test: Boolean) {
        val alreadyActive = Store.alarmActive(this)
        Store.setAlarm(this, true, summary, test)
        nm.notify(ID_ALARM, buildAlarmNotification(summary, test))
        if (!alreadyActive) startSiren()
    }

    @Synchronized
    private fun dismissAlarm() {
        val wasTest = Store.alarmIsTest(this)
        stopSiren()
        nm.cancel(ID_ALARM)
        if (!wasTest) {
            // Accept the changed page as the new normal.
            Store.pending(this)?.let { Store.setBaseline(this, it) }
        }
        Store.clearPending(this)
        Store.setAlarm(this, false, "", false)

        if (Store.monitoring(this)) {
            nm.notify(ID_STATUS, buildStatusNotification())
        } else {
            // Service was only running for a test alarm.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopEverything() {
        Store.setMonitoring(this, false)
        cancelTimer()
        stopSiren()
        nm.cancel(ID_ALARM)
        Store.clearPending(this)
        Store.setAlarm(this, false, "", false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Synchronized
    private fun startSiren() {
        if (player != null) return

        val am = getSystemService(AudioManager::class.java)
        savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Log.e(TAG, "could not play siren", e)
        }

        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    @Synchronized
    private fun stopSiren() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "player already stopped", e)
            }
            it.release()
        }
        player = null
        getSystemService(Vibrator::class.java)?.cancel()

        if (savedAlarmVolume >= 0) {
            getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            savedAlarmVolume = -1
        }
    }

    // ------------------------------------------------------------- notifications

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while a page is being watched" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM, "Page changed alarm", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Loud alert when the watched page changes"
                setSound(null, null) // the siren is played by the service itself
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun enterForeground() {
        val n = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                ID_STATUS, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ID_STATUS, n)
        }
    }

    private fun buildStatusNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 4,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP_MONITORING),
            PendingIntent.FLAG_IMMUTABLE
        )

        val last = Store.lastCheck(this)
        val line = if (last == 0L) {
            "Starting..."
        } else {
            val t = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last))
            "$t: ${Store.lastResult(this)}"
        }

        return Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Watching ${Store.url(this)}")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop monitoring", stop
                ).build()
            )
            .build()
    }

    private fun buildAlarmNotification(summary: String, test: Boolean): Notification {
        val screen = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismiss = PendingIntent.getService(
            this, 2,
            Intent(this, MonitorService::class.java).setAction(ACTION_DISMISS_ALARM),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (test) "TEST ALARM" else "PAGE CHANGED")
            .setContentText(Store.url(this))
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop alarm", dismiss
                ).build()
            )
            .build()
    }
}
