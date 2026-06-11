package dev.zselog.bluetoothhid.plugin.tcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives scans from the HID Barcode Scanner core app and forwards them to [TcpService], which
 * holds the persistent TCP connection (client keep-alive or server accept loop).
 * Contract strings live in [Protocol].
 */
class ScanReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScanReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_BARCODE_SCANNED) return
        try {
            // A scan is proof the core is alive — keep the service's watchdog satisfied.
            TcpService.noteCoreContact()

            val coreVersion = intent.getIntExtra(Protocol.EXTRA_PROTOCOL_VERSION, 0)
            if (coreVersion != Protocol.VERSION) {
                Log.w(TAG, "Core protocol version $coreVersion != ours (${Protocol.VERSION}) — update core/plugin")
            }

            val scanId = intent.getStringExtra(Protocol.EXTRA_SCAN_ID)
            val value = intent.getStringExtra(Protocol.EXTRA_PROCESSED_VALUE)
                ?: intent.getStringExtra(Protocol.EXTRA_RAW_VALUE)
                ?: return
            Log.i(TAG, "Received scan $scanId: '$value' — forwarding to TcpService")

            EventLog.add("Scan received: $value")
            TcpService.send(context, value, scanId)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in scan receiver — dropping scan", e)
        }
    }
}
