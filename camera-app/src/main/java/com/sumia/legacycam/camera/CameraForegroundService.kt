package com.sumia.legacycam.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CameraForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(CameraStreamingController.state.value))
                ensureNotificationUpdates()
                acquireWakeLock()

                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                CameraStreamingController.start(this, serverUrl, token)
            }

            ACTION_STOP -> {
                CameraStreamingController.stop("Device cam dihentikan dari foreground service.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: CameraServiceState): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            301,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, CameraForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            302,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (state.isRunning) "LegacyCam Cam Aktif" else "LegacyCam Cam Standby"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_app)
            .setContentTitle(title)
            .setContentText(state.status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.status))
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop Cam", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LegacyCam Camera Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Menjaga device cam tetap aktif di background."
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureNotificationUpdates() {
        if (notificationJob != null) return

        notificationJob = serviceScope.launch {
            CameraStreamingController.state.collectLatest { state ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(state))
                if (!state.isRunning && state.token.isBlank()) {
                    stopSelf()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LegacyCam:CameraWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "legacycam_camera_service"
        private const val NOTIFICATION_ID = 5501
        private const val ACTION_START = "camera_service_start"
        private const val ACTION_STOP = "camera_service_stop"
        private const val EXTRA_SERVER_URL = "extra_server_url"
        private const val EXTRA_TOKEN = "extra_token"

        fun start(context: Context, serverUrl: String, token: String) {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_TOKEN, token)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
