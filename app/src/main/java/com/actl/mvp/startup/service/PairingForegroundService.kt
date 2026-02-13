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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.actl.mvp.MainActivity
import com.actl.mvp.startup.AdbEndpoint
import com.actl.mvp.startup.AdbMdnsDiscovery
import com.actl.mvp.startup.directadb.DirectAdbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PairingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var directAdbManager: DirectAdbManager

    @Volatile
    private var pairingEndpoint: AdbEndpoint? = null

    @Volatile
    private var connectEndpoint: AdbEndpoint? = null

    private var discovery: AdbMdnsDiscovery? = null
    private var statusLine: String = "Waiting for ADB wireless endpoints"
    @Volatile
    private var shuttingDown: Boolean = false

    override fun onCreate() {
        super.onCreate()
        directAdbManager = DirectAdbManager(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (shuttingDown) {
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(statusLine))
                ensureDiscoveryStarted()
            }

            ACTION_SUBMIT_PAIR_CODE -> {
                val input = RemoteInput.getResultsFromIntent(intent)
                val pairCode = input?.getCharSequence(KEY_PAIR_CODE)?.toString().orEmpty()
                serviceScope.launch { pairAndConnect(pairCode) }
            }

            ACTION_STOP -> {
                shutdownAndRemoveNotification()
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(statusLine))
                ensureDiscoveryStarted()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shuttingDown = true
        runCatching { discovery?.stop() }
        discovery = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureDiscoveryStarted() {
        if (discovery != null) {
            return
        }
        discovery = AdbMdnsDiscovery(
            context = applicationContext,
            onState = { state ->
                pairingEndpoint = state.pairingEndpoint
                connectEndpoint = state.connectEndpoint
                val pair = pairingEndpoint?.let { "pair ${it.host}:${it.port}" } ?: "pair n/a"
                val connect = connectEndpoint?.let { "connect ${it.host}:${it.port}" } ?: "connect n/a"
                updateStatus("mDNS ready: $pair, $connect")
            },
            onLog = { line ->
                updateStatus(line)
            }
        )
        runCatching { discovery?.start() }
            .onSuccess {
                updateStatus("Discovering _adb-tls-pairing/_adb-tls-connect services")
            }
            .onFailure { error ->
                updateStatus("Discovery start failed: ${error.message}")
            }
    }

    private fun pairAndConnect(pairCode: String) {
        if (pairCode.isBlank()) {
            updateStatus("Pair failed: empty pair code")
            return
        }

        val pair = pairingEndpoint
        val connect = connectEndpoint

        if (pair == null) {
            updateStatus("Pair failed: pairing endpoint not discovered")
            return
        }
        if (connect == null) {
            updateStatus("Connect failed: connect endpoint not discovered")
            return
        }

        updateStatus("Pairing $LOOPBACK_HOST:${pair.port} (discovered ${pair.host}:${pair.port})")
        val pairResult = directAdbManager.pair(LOOPBACK_HOST, pair.port, pairCode)
        if (!pairResult.success) {
            updateStatus("Pair failed: ${pairResult.message}")
            return
        }

        updateStatus("Connecting $LOOPBACK_HOST:${connect.port} (discovered ${connect.host}:${connect.port})")
        val connectResult = directAdbManager.connect(LOOPBACK_HOST, connect.port, keepAlive = true)
        if (!connectResult.success) {
            updateStatus("Connect failed: ${connectResult.message}")
            return
        }
        shutdownAndRemoveNotification()
    }

    private fun updateStatus(message: String) {
        if (shuttingDown) {
            return
        }
        statusLine = message
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun shutdownAndRemoveNotification() {
        shuttingDown = true
        runCatching { discovery?.stop() }
        discovery = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun buildNotification(message: String): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val inputIntent = Intent(this, PairingForegroundService::class.java).setAction(ACTION_SUBMIT_PAIR_CODE)
        val inputPendingIntent = PendingIntent.getService(
            this,
            102,
            inputIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_PAIR_CODE)
            .setLabel("Input pair code")
            .build()

        val submitAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            "Input code and pair",
            inputPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val stopIntent = Intent(this, PairingForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_delete,
            "Stop",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ACTL Pairing Mode")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent)
            .addAction(submitAction)
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
            "ACTL Pairing",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Pairing mode for wireless ADB"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CHANNEL_ID = "actl_pairing"
        private const val NOTIFICATION_ID = 7101
        private const val KEY_PAIR_CODE = "pair_code"

        const val ACTION_START = "com.actl.mvp.action.START_PAIRING_MODE"
        const val ACTION_SUBMIT_PAIR_CODE = "com.actl.mvp.action.SUBMIT_PAIR_CODE"
        const val ACTION_STOP = "com.actl.mvp.action.STOP_PAIRING_MODE"

        fun start(context: Context) {
            val intent = Intent(context, PairingForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PairingForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
