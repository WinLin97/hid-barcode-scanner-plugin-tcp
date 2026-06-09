package dev.zselog.bluetoothhid.plugin.tcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles the core app's lifecycle + health channel (separate from scan delivery in [ScanReceiver]):
 *
 *  - SET_ENABLED(true)  → warm up the transport ahead of the first scan (start [TcpService]),
 *  - SET_ENABLED(false) → release it (stop [TcpService]),
 *  - PING               → answer with our current liveness so the core can self-heal a dead service.
 *
 * Being a manifest receiver, an incoming targeted broadcast cold-starts this app if needed and grants
 * the short window required to start the foreground service. Action/extra strings MUST match the
 * core's ExternalProtocol — keep in sync.
 */
class PluginControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PluginControlReceiver"

        const val ACTION_SET_ENABLED = "dev.fabik.bluetoothhid.plugin.action.SET_ENABLED"
        const val ACTION_PING = "dev.fabik.bluetoothhid.plugin.action.PING"
        const val EXTRA_ENABLED = "enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Any lifecycle/health message proves the core is alive — feed the service watchdog.
        TcpService.noteCoreContact()

        when (intent.action) {
            ACTION_SET_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                Log.i(TAG, "Core set enabled=$enabled")
                if (enabled) {
                    EventLog.add("Core enabled plugin — starting transport")
                    TcpService.start(context)
                } else {
                    EventLog.add("Core disabled plugin — stopping transport")
                    TcpService.stop(context)
                }
            }

            ACTION_PING -> {
                Log.i(TAG, "Core ping — running=${TcpService.isRunning}")
                ResultReporter.reportStatus(
                    context,
                    running = TcpService.isRunning,
                    detail = TcpService.statusSummary
                )
            }
        }
    }
}
