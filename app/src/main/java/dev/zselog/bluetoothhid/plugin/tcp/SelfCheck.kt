package dev.zselog.bluetoothhid.plugin.tcp

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager

/**
 * Diagnostic self-check, written to the [EventLog] on transport start and when the settings UI
 * opens. Every silent failure mode of the core↔plugin pipeline should be visible here, so "it
 * doesn't work" is always diagnosable from the Event log tab.
 */
object SelfCheck {

    /**
     * The RECEIVE_SCANS grant is lost when the core app (which defines the permission) is
     * reinstalled — the core's broadcasts are then silently dropped. See [PluginState.NO_PERMISSION].
     */
    fun hasReceivePermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Protocol.PERMISSION_RECEIVE_SCANS) == PackageManager.PERMISSION_GRANTED

    private fun isCoreInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(Protocol.CORE_PACKAGE, 0) }.isSuccess

    private fun areNotificationsEnabled(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false

    private fun isNetworkAvailable(ctx: Context): Boolean =
        ctx.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

    private fun isConfigValid(ctx: Context): Boolean = when (TcpConfig.getMode(ctx)) {
        TcpConfig.Mode.CLIENT ->
            TcpConfig.getHost(ctx).isNotBlank() && TcpConfig.getClientPort(ctx) in 1..65535
        TcpConfig.Mode.SERVER -> TcpConfig.getServerPort(ctx) in 1..65535
    }

    /** Runs all checks; one summary line when everything is fine, one line per problem otherwise. */
    fun log(ctx: Context) {
        val issues = buildList {
            if (!hasReceivePermission(ctx))
                add("RECEIVE_SCANS not granted — reinstall this plugin (the grant is lost when the core app is reinstalled)")
            if (!isCoreInstalled(ctx))
                add("Core app (HID Barcode Scanner) is not installed")
            if (!areNotificationsEnabled(ctx))
                add("Notifications are disabled — the transport's status notification is invisible")
            if (!isNetworkAvailable(ctx))
                add("No active network connection")
            if (!isConfigValid(ctx))
                add("Transport configuration is incomplete — check host/port in settings")
        }
        if (issues.isEmpty()) {
            EventLog.add("Self-check OK (permission, core app, notifications, network, config)")
        } else {
            issues.forEach { EventLog.add("Self-check: $it") }
        }
    }
}
