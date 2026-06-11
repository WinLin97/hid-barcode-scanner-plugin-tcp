package dev.zselog.bluetoothhid.plugin.tcp

/**
 * Broadcast contract with the HID Barcode Scanner core app — the single place in this plugin
 * where the contract strings live. Mirrors the core's ExternalProtocol object; keep both in sync.
 * The manifest duplicates the inbound action strings (intent-filters can't reference Kotlin
 * constants) — update it together with this file.
 */
object Protocol {
    const val CORE_PACKAGE = "dev.fabik.bluetoothhid"

    /** Permission gating both directions of the scan/lifecycle channel. Granted at install. */
    const val PERMISSION_RECEIVE_SCANS = "dev.fabik.bluetoothhid.permission.RECEIVE_SCANS"

    /**
     * Single int, compared for equality only. Versioning shipped together with the contract, so
     * both sides have always sent it; its job is catching FUTURE drift (one app updated without
     * the other → the core shows "update the plugin"). Bumped on BOTH sides whenever message
     * semantics change.
     */
    const val VERSION = 1

    // ── Core → plugin ───────────────────────────────────────────────────────────────────────
    const val ACTION_BARCODE_SCANNED = "dev.fabik.bluetoothhid.action.BARCODE_SCANNED"
    const val ACTION_SET_ENABLED = "dev.fabik.bluetoothhid.plugin.action.SET_ENABLED"
    const val ACTION_PING = "dev.fabik.bluetoothhid.plugin.action.PING"

    const val EXTRA_SCAN_ID = "scan_id"
    const val EXTRA_RAW_VALUE = "raw_value"
    const val EXTRA_PROCESSED_VALUE = "processed_value"
    const val EXTRA_ENABLED = "enabled"

    // ── Plugin → core ───────────────────────────────────────────────────────────────────────
    const val ACTION_SEND_RESULT = "dev.fabik.bluetoothhid.action.SEND_RESULT"
    const val ACTION_PLUGIN_STATUS = "dev.fabik.bluetoothhid.plugin.action.STATUS"

    const val EXTRA_RESULT_OK = "result_ok"
    const val EXTRA_RESULT_DETAIL = "result_detail"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_RUNNING = "running"
    const val EXTRA_STATUS_DETAIL = "status_detail"

    /** String — [PluginState].name. Machine-readable; the core maps it to its status UI. */
    const val EXTRA_STATE = "state"

    /** Int — [VERSION] of the sender, on every message in both directions. */
    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
}

/**
 * Machine-readable transport state reported to the core in [Protocol.EXTRA_STATE].
 * [Protocol.EXTRA_STATUS_DETAIL] stays human-only — the core must never parse it (it's localized).
 */
enum class PluginState {
    /** Transport not running (stopped / never started). */
    IDLE,

    /** Transport starting up, no listener/connection yet. */
    STARTING,

    /** Client mode: trying to reach the configured host. */
    CONNECTING,

    /** Peer connected (client socket up, or ≥1 client on the server). */
    CONNECTED,

    /** Server mode: listening, no clients yet. */
    LISTENING,

    /** Transport failed or was stopped by the system (e.g. FGS time limit). */
    ERROR,

    /** OS denied the background service start — user must allow Autostart / battery exemption. */
    BLOCKED,

    /**
     * The RECEIVE_SCANS grant is missing (typically after the core app was reinstalled) — the
     * core's broadcasts can no longer reach this plugin; reinstalling the plugin restores it.
     * Only deliverable because the plugin→core channel is not permission-gated.
     */
    NO_PERMISSION,
}
