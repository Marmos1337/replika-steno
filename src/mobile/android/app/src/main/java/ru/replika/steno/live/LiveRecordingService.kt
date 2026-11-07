package ru.replika.steno.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.replika.steno.MainActivity
import ru.replika.steno.R
import ru.replika.steno.ReplikaApp
import ru.replika.steno.core.live.LiveState
import ru.replika.steno.core.live.LiveStatus

/**
 * Keeps a live recording going with the screen off or the app in the background: a foreground
 * service of type microphone with an ongoing notification («Идёт запись · 03:12», Pause/Stop).
 * The session itself lives in AppGraph; the service only holds the process in the foreground and
 * forwards the notification buttons. It stops itself when the session ends.
 */
class LiveRecordingService : Service() {
    private val graph get() = (application as ReplikaApp).graph
    private val scope = MainScope()
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown: Pair<LiveStatus, Long>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(graph.live.value ?: LiveState()),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }
        if (started.isFailure) {
            // e.g. no RECORD_AUDIO for a file-source test run: recording still works while the app is open
            Log.w(TAG, "live service: cannot go foreground", started.exceptionOrNull())
            stopSelf()
            return
        }
        // the worker must keep decoding with the screen off; the session cap is 2.5 h
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "replika:live")
            ?.apply { acquire(3 * 60 * 60 * 1000L) }
        scope.launch {
            graph.live.collect { s ->
                if (s == null || s.status.terminal) { stopSelf(); return@collect }
                val key = s.status to s.elapsedMs / 1000
                if (key != shown) {
                    shown = key
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> graph.pauseLive()
            ACTION_RESUME -> graph.resumeLive()
            ACTION_STOP -> graph.stopLive()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Живая запись", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Показывает, что идёт запись с микрофона"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun build(s: LiveState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = clock(s.elapsedMs)
        val title = when (s.status) {
            LiveStatus.PAUSED -> "Запись на паузе · $time"
            LiveStatus.FINISHING -> "Завершаю запись · $time"
            LiveStatus.STARTING -> "Начинаю запись"
            else -> "Идёт запись · $time"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(title)
            .setContentText("Распознавание на телефоне, без интернета")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (s.status) {
            LiveStatus.RECORDING -> b.addAction(0, "Пауза", action(ACTION_PAUSE))
            LiveStatus.PAUSED -> b.addAction(0, "Продолжить", action(ACTION_RESUME))
            else -> Unit
        }
        if (s.status.capturing) b.addAction(0, "Стоп", action(ACTION_STOP))
        return b.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, LiveRecordingService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "Replika"
        private const val CHANNEL = "live"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_PAUSE = "ru.replika.steno.live.PAUSE"
        private const val ACTION_RESUME = "ru.replika.steno.live.RESUME"
        private const val ACTION_STOP = "ru.replika.steno.live.STOP"

        /** Returns false when the system refused to start a foreground service (app in background). */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, LiveRecordingService::class.java))
        }.onFailure { Log.w(TAG, "live service not started", it) }.isSuccess

        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
        }
    }
}
