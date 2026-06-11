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
            val state: PluginState = PluginState.IDLE,
        )

        private val _status = MutableStateFlow(TransportStatus())
        val status: StateFlow<TransportStatus> = _status.asStateFlow()

        // Convenience reads for PluginControlReceiver's health ping (kept as the public surface).
        val isRunning: Boolean get() = _status.value.running
        val statusSummary: String? get() = _status.value.summary
        val currentState: PluginState get() = _status.value.state

        // Rate limit for the "start blocked" EventLog entry: the core retries every 20s forever,
        // which would flush the 100-entry log with duplicates of the same hint.
        @Volatile
        private var lastBlockedLogMs = 0L
        private const val BLOCKED_LOG_INTERVAL_MS = 5 * 60_000L

        // Wall-clock of the last message received from the core; drives the watchdog.
        @Volatile
        private var lastCoreContactMs = 0L

        /** Receivers call this on ANY inbound core broadcast to keep the watchdog satisfied. */
        fun noteCoreContact() {
            lastCoreContactMs = System.currentTimeMillis()
        }

        /**
         * Starts the service, swallowing the OS's background-start denials
         * ([android.app.ForegroundServiceStartNotAllowedException] /
         * [android.app.BackgroundServiceStartNotAllowedException]). These fire when a broadcast
         * receiver tries to (re)start the service while the app is in the background and the OS
         * didn't grant a start window (common on Android 12+, and aggressively on OEMs like MIUI
         * without "Autostart"). Letting them propagate crashes the receiver, which gets the whole
         * app flagged as a "bad process" and backed off — so a single blocked start would stop the
         * plugin from ever being revived. Returns true if the start was accepted.
         */
        private fun safelyStart(ctx: Context, intent: Intent, foreground: Boolean): Boolean =
            runCatching {
                if (foreground) ctx.startForegroundService(intent) else ctx.startService(intent)
            }.onFailure { e ->
                Log.w(TAG, "Service start blocked (background/OEM restriction): ${e.message}")
                val now = System.currentTimeMillis()
                if (now - lastBlockedLogMs > BLOCKED_LOG_INTERVAL_MS) {
                    lastBlockedLogMs = now
                    EventLog.add("Couldn't start transport in background — allow Autostart / disable battery optimisation for this app")
                }
            }.isSuccess

        fun start(ctx: Context) {
            val ok = safelyStart(ctx, Intent(ctx, TcpService::class.java).setAction(ACTION_START), foreground = true)
            // Tell the core we're down so its UI reflects reality (and can show the remedy)
            // instead of waiting forever.
            if (!ok) ResultReporter.reportStatus(
                ctx,
                running = false,
                state = PluginState.BLOCKED,
                detail = "start blocked by OS — allow Autostart / disable battery optimisation"
            )
        }

        fun restart(ctx: Context) {
            safelyStart(ctx, Intent(ctx, TcpService::class.java).setAction(ACTION_RESTART), foreground = true)
        }

        fun stop(ctx: Context) {
            safelyStart(ctx, Intent(ctx, TcpService::class.java).setAction(ACTION_STOP), foreground = false)
        }

        fun send(ctx: Context, value: String, scanId: String?) {
            val intent = Intent(ctx, TcpService::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_VALUE, value)
                .putExtra(EXTRA_SCAN_ID, scanId)
            val ok = safelyStart(ctx, intent, foreground = true)
            // A scan we couldn't even hand to the service is a failed delivery — report it back.
            if (!ok) ResultReporter.report(ctx, scanId, ok = false, detail = "delivery blocked by OS (background)")
        }
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
                SelfCheck.log(this)
                ensureStarted()
            }
            ACTION_RESTART -> {
                EventLog.add("Service restart requested")
                SelfCheck.log(this)
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
                    EventLog.add(
                        "No contact from core for ${silentMs / 1000}s — stopping transport " +
                            "(by design: it only runs while the core app is active, to save battery)"
                    )
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
        // A missing RECEIVE_SCANS grant (core reinstalled) trumps everything: the transport may
        // run, but no scan can ever reach it. Reporting it here is the only way the core can show
        // the remedy — its own broadcasts no longer reach us at all.
        val noPermission = !SelfCheck.hasReceivePermission(this)
        val state = when {
            noPermission -> PluginState.NO_PERMISSION
            c == null -> PluginState.IDLE
            connected -> PluginState.CONNECTED
            c.isListening() ->
                if (TcpConfig.getMode(this) == TcpConfig.Mode.SERVER) PluginState.LISTENING
                else PluginState.CONNECTING
            else -> PluginState.STARTING
        }
        val summary = when {
            noPermission -> getString(R.string.status_no_permission)
            c == null -> null
            connected -> getString(R.string.status_suffix_connected, statusText())
            c.isListening() -> getString(R.string.status_suffix_waiting, statusText())
            else -> statusText()
        }
        val next = TransportStatus(running = c != null, connected = connected, summary = summary, state = state)
        if (next == _status.value) return // unchanged — don't spam the core
        _status.value = next
        // Proactively announce the change to the core so its UI/health reflects start/connect/stop
        // within ms instead of waiting for the next liveness ping (also acts as a SET_ENABLED ack).
        // The ping path stays an explicit reply (see PluginControlReceiver); this is in addition.
        ResultReporter.reportStatus(this, running = next.running, state = next.state, detail = next.summary)
    }

    /**
     * Android 15+: some FGS types have a hard runtime cap; when it expires the system calls this
     * and the service MUST stop promptly or it is killed with a RemoteServiceException. The
     * specialUse type currently has no cap — this is the safety net in case that ever changes.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout (type=$fgsType) — stopping transport")
        EventLog.add("System time limit for the transport service reached — stopping")
        stopEverything()
        // Sent after stopEverything so this (with the reason) is the last status the core keeps.
        ResultReporter.reportStatus(
            this,
            running = false,
            state = PluginState.ERROR,
            detail = "stopped by system (foreground-service time limit)"
        )
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
        TcpConfig.Mode.CLIENT -> getString(
            R.string.status_client,
            TcpConfig.getHost(this),
            TcpConfig.getClientPort(this).toString()
        )
        TcpConfig.Mode.SERVER ->
            getString(R.string.status_server, TcpConfig.getServerPort(this).toString())
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_title))
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
