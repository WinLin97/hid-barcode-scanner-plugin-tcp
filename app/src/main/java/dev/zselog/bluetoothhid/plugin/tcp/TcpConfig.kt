package dev.zselog.bluetoothhid.plugin.tcp

import android.content.Context

/**
 * Plugin-local TCP configuration, persisted in SharedPreferences. Mirrors the settings the core
 * app's TcpController used to read from DataStore — here they live in the plugin instead.
 */
object TcpConfig {
    enum class Mode { CLIENT, SERVER }

    private const val PREFS = "tcp_config"

    private const val KEY_MODE = "mode"
    private const val KEY_HOST = "host"
    private const val KEY_CLIENT_PORT = "client_port"
    private const val KEY_CONNECT_TIMEOUT = "connect_timeout_ms"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_MAX_CLIENTS = "max_clients"
    private const val KEY_IDLE_TIMEOUT = "idle_timeout_ms"

    const val DEFAULT_PORT = 51000
    const val DEFAULT_CONNECT_TIMEOUT_MS = 3000
    const val DEFAULT_MAX_CLIENTS = 5
    const val DEFAULT_IDLE_TIMEOUT_MS = 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Mode =
        runCatching { Mode.valueOf(prefs(ctx).getString(KEY_MODE, Mode.CLIENT.name)!!) }
            .getOrDefault(Mode.CLIENT)

    // Client
    fun getHost(ctx: Context): String = prefs(ctx).getString(KEY_HOST, "") ?: ""
    fun getClientPort(ctx: Context): Int = prefs(ctx).getInt(KEY_CLIENT_PORT, DEFAULT_PORT)
    fun getConnectTimeoutMs(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT_MS)

    // Server
    fun getServerPort(ctx: Context): Int = prefs(ctx).getInt(KEY_SERVER_PORT, DEFAULT_PORT)
    fun getMaxClients(ctx: Context): Int = prefs(ctx).getInt(KEY_MAX_CLIENTS, DEFAULT_MAX_CLIENTS)
    fun getIdleTimeoutMs(ctx: Context): Int =
        prefs(ctx).getInt(KEY_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT_MS)

    fun save(
        ctx: Context,
        mode: Mode,
        host: String,
        clientPort: Int,
        connectTimeoutMs: Int,
        serverPort: Int,
        maxClients: Int,
        idleTimeoutMs: Int,
    ) {
        prefs(ctx).edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_HOST, host.trim())
            .putInt(KEY_CLIENT_PORT, clientPort)
            .putInt(KEY_CONNECT_TIMEOUT, connectTimeoutMs)
            .putInt(KEY_SERVER_PORT, serverPort)
            .putInt(KEY_MAX_CLIENTS, maxClients)
            .putInt(KEY_IDLE_TIMEOUT, idleTimeoutMs)
            .apply()
    }
}
