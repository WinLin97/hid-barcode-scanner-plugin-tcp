package dev.zselog.bluetoothhid.plugin.tcp

import android.content.Context
import android.content.Intent
import android.util.Log

/** Sends the optional delivery-status broadcast back to the core app (correlated by scan_id). */
object ResultReporter {
    private const val TAG = "ResultReporter"
    private const val CORE_PACKAGE = "dev.fabik.bluetoothhid"

    const val ACTION_SEND_RESULT = "dev.fabik.bluetoothhid.action.SEND_RESULT"
    const val EXTRA_SCAN_ID = "scan_id"
    const val EXTRA_RESULT_OK = "result_ok"
    const val EXTRA_RESULT_DETAIL = "result_detail"

    const val ACTION_PLUGIN_STATUS = "dev.fabik.bluetoothhid.plugin.action.STATUS"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_RUNNING = "running"
    const val EXTRA_STATUS_DETAIL = "status_detail"

    fun report(ctx: Context, scanId: String?, ok: Boolean, detail: String) {
        val intent = Intent(ACTION_SEND_RESULT).apply {
            setPackage(CORE_PACKAGE)
            putExtra(EXTRA_SCAN_ID, scanId)
            putExtra(EXTRA_RESULT_OK, ok)
            putExtra(EXTRA_RESULT_DETAIL, detail)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "Failed to report result back to core", it) }
    }

    /** Answers the core's liveness ping (ACTION_PLUGIN_PING) with our current transport state. */
    fun reportStatus(ctx: Context, running: Boolean, detail: String?) {
        val intent = Intent(ACTION_PLUGIN_STATUS).apply {
            setPackage(CORE_PACKAGE)
            putExtra(EXTRA_PACKAGE, ctx.packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_STATUS_DETAIL, detail)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onFailure { Log.e(TAG, "Failed to report status back to core", it) }
    }
}
