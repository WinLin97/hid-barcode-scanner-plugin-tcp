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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the persistent [TcpController] (server accept loop / client
 * keep-alive). A short-lived BroadcastReceiver can't keep a socket open between scans, so the
 * scan receiver forwards values here and the service maintains the connection.
 *
 * The service only lives while the core wants it: every inbound core message (scan, lifecycle,
 * ping) refreshes [lastCoreContactMs], and a watchdog self-stops the service if the core goes
 * silent for [CORE_CONTACT_TIMEOUT_MS] (core killed / uninstalled / switched away without a clean
 * stop). Combined with START_NOT_STICKY this prevents a "zombie" transport from outliving the core.
 */
class TcpService : Service() {
    companion object {
        private const val TAG = "TcpService"
        private const val CHANNEL_ID = "tcp_transport"
        private const val NOTIF_ID = 1

        // Watchdog: must comfortably exceed the core's ping interval (20s). ~3.5 missed pings.
        private const val WATCHDOG_CHECK_MS = 20_000L
        private const val CORE_CONTACT_TIMEOUT_MS = 70_000L

        const val ACTION_START = "dev.zselog.bluetoothhid.plugin.tcp.START"
        const val ACTION_STOP = "dev.zselog.bluetoothhid.plugin.tcp.STOP"
        const val ACTION_RESTART = "dev.zselog.bluetoothhid.plugin.tcp.RESTART"
        const val ACTION_SEND = "dev.zselog.bluetoothhid.plugin.tcp.SEND"
        const val EXTRA_VALUE = "value"
        const val EXTRA_SCAN_ID = "scan_id"

        /** Live transport state, observable by the plugin UI and read by the ping reply. */
        data class TransportStatus(
            val running: Boolean = false,
            val connected: Boolean = false,
            val summary: String? = null,
        )

        private val _status = MutableStateFlow(TransportStatus())
        val status: StateFlow<TransportStatus> = _status.asStateFlow()

        // Convenience reads for PluginControlReceiver's health ping (kept as the public surface).
        val isRunning: Boolean get() = _status.value.running
        val statusSummary: String? get() = _status.value.summary

        // Wall-clock of the last message received from the core; drives the watchdog.
        @Volatile
        private var lastCoreContactMs = 0L

        /** Receivers call this on ANY inbound core broadcast to keep the watchdog satisfied. */
        fun noteCoreContact() {
            lastCoreContactMs = System.currentTimeMillis()
        }

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, TcpService::class.java).setAction(ACTION_START))

        fun restart(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, TcpService::class.java).setAction(ACTION_RESTART))

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(statusText()))

        when (intent?.action) {
            ACTION_START -> {
                EventLog.add("Service start requested")
                ensureStarted()
            }
            ACTION_RESTART -> {
                EventLog.add("Service restart requested")
                restartWithCurrentConfig()
            }

            ACTION_SEND -> {
                ensureStarted()
                val value = intent.getStringExtra(EXTRA_VALUE)
                val scanId = intent.getStringExtra(EXTRA_SCAN_ID)
                if (value != null) {
                    EventLog.add("Sending scan over TCP: $value")
                    controller?.sendProcessedData(value) { ok, detail ->
                        EventLog.add("Send result: ${if (ok) "ok" else "failed"} - $detail")
                        ResultReporter.report(this, scanId, ok = ok, detail = detail)
                    } ?: run {
                        EventLog.add("Send result: failed - TCP controller not available")
                        ResultReporter.report(this, scanId, ok = false, detail = "TCP controller not available")
                    }
                }
            }

            ACTION_STOP -> {
                EventLog.add("Service stop requested")
                stopEverything()
            }
        }
        refreshStatus()
        // NOT_STICKY: if the OS kills us, stay dead instead of being recreated with a null intent
        // (which would be a do-nothing "zombie" foreground service). The core's heartbeat detects
        // the gap and revives us cleanly via SET_ENABLED; a scan would too.
        return START_NOT_STICKY
    }

    /** Single watchdog coroutine: self-stop when the core stops talking to us. */
    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_MS)
                val silentMs = System.currentTimeMillis() - lastCoreContactMs
                if (silentMs > CORE_CONTACT_TIMEOUT_MS) {
                    Log.i(TAG, "Core silent for ${silentMs}ms — self-stopping (core unavailable)")
                    EventLog.add("No contact from core for ${silentMs / 1000}s — stopping transport")
                    stopEverything()
                    break
                }
            }
        }
    }

    /** Tear down transport + foreground state and stop the service. */
    private fun stopEverything() {
        watchdogJob?.cancel()
        watchdogJob = null
        controller?.stop()
        controller = null
        refreshStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Recompute the observable transport state from the live controller. */
    private fun refreshStatus() {
        val c = controller
        val connected = c?.isConnected() == true
        val summary = if (c == null) null else statusText() + when {
            connected -> " (connected)"
            c.isListening() -> " (waiting)"
            else -> ""
        }
        _status.value = TransportStatus(running = c != null, connected = connected, summary = summary)
    }

    /** Get-or-create the controller, wiring it to push live status updates on connection changes. */
    private fun obtainController(): TcpController =
        controller ?: TcpController(applicationContext).also {
            it.setConnectedAddressesCallback { refreshStatus() }
            controller = it
        }

    private fun restartWithCurrentConfig() {
        controller?.stop()
        controller = null
        obtainController()
        ensureStarted()
    }

    private fun ensureStarted() {
        // Any path that (re)starts the transport is itself a core contact; seed it so a freshly
        // started service gets the full grace window before the watchdog can fire.
        noteCoreContact()
        ensureWatchdog()
        val c = obtainController()
        when (TcpConfig.getMode(this)) {
            TcpConfig.Mode.CLIENT -> c.startClient() // idempotent
            TcpConfig.Mode.SERVER -> c.startServer()
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
        watchdogJob?.cancel()
        serviceScope.cancel()
        controller?.stop()
        controller = null
        refreshStatus()
        Log.i(TAG, "TcpService destroyed")
        super.onDestroy()
    }
}
