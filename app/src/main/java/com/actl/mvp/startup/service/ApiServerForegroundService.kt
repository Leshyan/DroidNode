package com.actl.mvp.startup.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.actl.mvp.MainActivity
import com.actl.mvp.api.framework.ApiModuleRegistry
import com.actl.mvp.api.framework.ApiServer

class ApiServerForegroundService : Service() {

    private val apiServer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ApiServer(ApiModuleRegistry.defaultModules())
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerAndSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting API server..."))
                if (!started) {
                    runCatching { apiServer.start(ApiServer.DEFAULT_PORT) }
                        .onSuccess {
                            started = true
                            NotificationManagerCompat.from(this).notify(
                                NOTIFICATION_ID,
                                buildNotification("API server running on :${ApiServer.DEFAULT_PORT}")
                            )
                        }
                        .onFailure { error ->
                            NotificationManagerCompat.from(this).notify(
                                NOTIFICATION_ID,
                                buildNotification("API server failed: ${error.message}")
                            )
                        }
                } else {
                    NotificationManagerCompat.from(this).notify(
                        NOTIFICATION_ID,
                        buildNotification("API server running on :${ApiServer.DEFAULT_PORT}")
                    )
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        runCatching { apiServer.stop() }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServerAndSelf() {
        runCatching { apiServer.stop() }
        started = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun buildNotification(message: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            801,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ApiServerForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            802,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete,
            "Stop API",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("ACTL API Service")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPendingIntent)
            .addAction(stopAction)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ACTL API Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keep API server alive for remote control"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "actl_api_service"
        private const val NOTIFICATION_ID = 7201
        private const val ACTION_START = "com.actl.mvp.action.START_API_SERVICE"
        private const val ACTION_STOP = "com.actl.mvp.action.STOP_API_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ApiServerForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ApiServerForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

