package com.sumia.legacycam.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var serviceStopping: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceStopping = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                startCameraSession(serverUrl, token)
            }

            ACTION_STOP -> {
                serviceStopping = true
                CameraSessionStore.markActive(this, false)
                CameraStreamingController.stop("Device cam dihentikan dari foreground service.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                releaseWifiLock()
                stopSelf()
            }

            else -> {
                if (!restoreSavedSession()) {
                    serviceStopping = true
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        val shouldRestart = !serviceStopping && CameraSessionStore.loadActive(this) != null
        releaseWakeLock()
        releaseWifiLock()
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        if (shouldRestart) {
            val session = CameraSessionStore.loadActive(this) ?: return
            start(
                context = applicationContext,
                serverUrl = session.serverUrl,
                token = session.token,
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = CameraSessionStore.loadActive(this)
        if (session != null) {
            start(
                context = applicationContext,
                serverUrl = session.serverUrl,
                token = session.token,
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(state: CameraServiceState): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            301,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_running_text)))
            .setOngoing(state.isRunning)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureNotificationUpdates() {
        if (notificationJob != null) return

        notificationJob = serviceScope.launch {
            CameraStreamingController.state.collectLatest { state ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(state))
                val hasActiveSession = CameraSessionStore.loadActive(this@CameraForegroundService) != null
                if (!state.isRunning && state.token.isBlank() && !hasActiveSession && serviceStopping) {
                    stopSelf()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antVrs:CameraWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "antVrs:CameraWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
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

    private fun startCameraSession(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            serviceStopping = true
            stopSelf()
            return
        }

        serviceStopping = false
        CameraSessionStore.saveBinding(this, serverUrl, token)
        CameraSessionStore.markActive(this, true)
        startServiceInForeground()
        acquireWakeLock()
        acquireWifiLock()
        CameraStreamingController.start(
            context = this,
            serverUrl = serverUrl,
            token = token,
            deviceId = CameraSessionStore.getOrCreateDeviceId(this),
        )
        ensureNotificationUpdates()
    }

    private fun restoreSavedSession(): Boolean {
        val session = CameraSessionStore.loadActive(this) ?: return false
        startCameraSession(session.serverUrl, session.token)
        return true
    }

    private fun startServiceInForeground() {
        val notification = buildNotification(CameraStreamingController.state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            return
        }

        startForeground(NOTIFICATION_ID, notification)
    }
}
