package dev.zselog.bluetoothhid.plugin.tcp

import android.content.Context
import android.content.Intent
import android.util.Log

/** Sends the optional delivery-status broadcast back to the core app (correlated by scan_id). */
object ResultReporter {
    private const val TAG = "ResultReporter"

    fun report(ctx: Context, scanId: String?, ok: Boolean, detail: String) {
        val intent = Intent(Protocol.ACTION_SEND_RESULT).apply {
            setPackage(Protocol.CORE_PACKAGE)
            putExtra(Protocol.EXTRA_PROTOCOL_VERSION, Protocol.VERSION)
            // Sender package: with several plugins enabled the core must be able to attribute
            // each result ("which transport failed?"), same as the STATUS channel.
            putExtra(Protocol.EXTRA_PACKAGE, ctx.packageName)
            putExtra(Protocol.EXTRA_SCAN_ID, scanId)
            putExtra(Protocol.EXTRA_RESULT_OK, ok)
            putExtra(Protocol.EXTRA_RESULT_DETAIL, detail)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "Failed to report result back to core", it) }
    }

    /**
     * Reports the transport state to the core: as the answer to its liveness ping, and proactively
     * on every state change. [state] is the machine-readable contract value; [detail] is for humans.
     */
    fun reportStatus(ctx: Context, running: Boolean, state: PluginState, detail: String?) {
        val intent = Intent(Protocol.ACTION_PLUGIN_STATUS).apply {
            setPackage(Protocol.CORE_PACKAGE)
            putExtra(Protocol.EXTRA_PROTOCOL_VERSION, Protocol.VERSION)
            putExtra(Protocol.EXTRA_PACKAGE, ctx.packageName)
            putExtra(Protocol.EXTRA_RUNNING, running)
            putExtra(Protocol.EXTRA_STATE, state.name)
            putExtra(Protocol.EXTRA_STATUS_DETAIL, detail)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "Failed to report status back to core", it) }
    }
}
