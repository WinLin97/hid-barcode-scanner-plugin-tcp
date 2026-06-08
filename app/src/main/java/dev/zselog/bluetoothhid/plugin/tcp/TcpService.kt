package dev.zselog.bluetoothhid.plugin.tcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that hosts the persistent [TcpController] (server accept loop / client
 * keep-alive). A short-lived BroadcastReceiver can't keep a socket open between scans, so the
 * scan receiver forwards values here and the service maintains the connection.
 */
class TcpService : Service() {
    companion object {
        private const val TAG = "TcpService"
        private const val CHANNEL_ID = "tcp_transport"
        private const val NOTIF_ID = 1

        const val ACTION_START = "dev.zselog.bluetoothhid.plugin.tcp.START"
        const val ACTION_STOP = "dev.zselog.bluetoothhid.plugin.tcp.STOP"
        const val ACTION_SEND = "dev.zselog.bluetoothhid.plugin.tcp.SEND"
        const val EXTRA_VALUE = "value"
        const val EXTRA_SCAN_ID = "scan_id"

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, TcpService::class.java).setAction(ACTION_START))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, TcpService::class.java).setAction(ACTION_STOP))

        fun send(ctx: Context, value: String, scanId: String?) =
            ctx.startForegroundService(
                Intent(ctx, TcpService::class.java)
                    .setAction(ACTION_SEND)
                    .putExtra(EXTRA_VALUE, value)
                    .putExtra(EXTRA_SCAN_ID, scanId)
            )
    }

    private var controller: TcpController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(statusText()))

        when (intent?.action) {
            ACTION_START -> ensureStarted()

            ACTION_SEND -> {
                ensureStarted()
                val value = intent.getStringExtra(EXTRA_VALUE)
                val scanId = intent.getStringExtra(EXTRA_SCAN_ID)
                if (value != null) {
                    controller?.sendProcessedData(value)
                    // The controller delivers/retries internally and doesn't report per-send
                    // success synchronously, so we optimistically report "forwarded".
                    ResultReporter.report(this, scanId, ok = true, detail = "forwarded over TCP")
                }
            }

            ACTION_STOP -> {
                controller?.stop()
                controller = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureStarted() {
        if (controller == null) controller = TcpController(applicationContext)
        when (TcpConfig.getMode(this)) {
            TcpConfig.Mode.CLIENT -> controller?.startClient() // idempotent
            TcpConfig.Mode.SERVER -> controller?.startServer()
        }
    }

    private fun statusText(): String = when (TcpConfig.getMode(this)) {
        TcpConfig.Mode.CLIENT -> "TCP client → ${TcpConfig.getHost(this)}:${TcpConfig.getClientPort(this)}"
        TcpConfig.Mode.SERVER -> "TCP server on port ${TcpConfig.getServerPort(this)}"
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TCP Transport", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TCP Transport Plugin")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        Log.i(TAG, "TcpService destroyed")
        super.onDestroy()
    }
}
