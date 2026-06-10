package dev.zselog.bluetoothhid.plugin.tcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives scans from the HID Barcode Scanner core app and forwards them to [TcpService], which
 * holds the persistent TCP connection (client keep-alive or server accept loop).
 *
 * The action/extra strings below MUST match the core app's ExternalProtocol — keep in sync.
 */
class ScanReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScanReceiver"

        const val ACTION_BARCODE_SCANNED = "dev.fabik.bluetoothhid.action.BARCODE_SCANNED"
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_RAW_VALUE = "raw_value"
        const val EXTRA_PROCESSED_VALUE = "processed_value"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BARCODE_SCANNED) return
        try {
            // A scan is proof the core is alive — keep the service's watchdog satisfied.
            TcpService.noteCoreContact()

            val scanId = intent.getStringExtra(EXTRA_SCAN_ID)
            val value = intent.getStringExtra(EXTRA_PROCESSED_VALUE)
                ?: intent.getStringExtra(EXTRA_RAW_VALUE)
                ?: return
            Log.i(TAG, "Received scan $scanId: '$value' — forwarding to TcpService")

            EventLog.add("Scan received: $value")
            TcpService.send(context, value, scanId)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in scan receiver — dropping scan", e)
        }
    }
}
