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
 * Being a manifest receiver, an incoming targeted broadcast cold-starts this app if needed. Note:
 * on Android 12+ that does NOT exempt the foreground-service start from background restrictions —
 * [TcpService.safelyStart] handles the denial and reports BLOCKED. Contract strings live in [Protocol].
 */
class PluginControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PluginControlReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // Any lifecycle/health message proves the core is alive — feed the service watchdog.
            TcpService.noteCoreContact()

            when (intent.action) {
                Protocol.ACTION_SET_ENABLED -> {
                    val enabled = intent.getBooleanExtra(Protocol.EXTRA_ENABLED, false)
                    Log.i(TAG, "Core set enabled=$enabled")
                    if (enabled) {
                        EventLog.add("Core enabled plugin — starting transport")
                        TcpService.start(context)
                    } else {
                        EventLog.add("Core disabled plugin — stopping transport")
                        TcpService.stop(context)
                    }
                }

                Protocol.ACTION_PING -> {
                    Log.i(TAG, "Core ping — running=${TcpService.isRunning} state=${TcpService.currentState}")
                    ResultReporter.reportStatus(
                        context,
                        running = TcpService.isRunning,
                        state = TcpService.currentState,
                        detail = TcpService.statusSummary
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in plugin control receiver", e)
        }
    }
}
