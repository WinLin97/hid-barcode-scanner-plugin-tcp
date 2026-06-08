package dev.zselog.bluetoothhid.plugin.tcp

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** Local IPv4 addresses, for showing the server's reachable address(es). */
private fun localIpAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { it.isUp && !it.isLoopback }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.filterIsInstance<Inet4Address>()
        ?.filter { !it.isLoopbackAddress }
        ?.mapNotNull { it.hostAddress }
        ?.toList()
}.getOrNull() ?: emptyList()

data class TcpStatusData(
    val serverAddresses: List<String> = emptyList(),
    val clientAddresses: List<String> = emptyList(),
    val clientTarget: String? = null,
) {
    val isEmpty get() = serverAddresses.isEmpty() && clientAddresses.isEmpty() && clientTarget == null
}

/**
 * Full TCP transport (server + client) lifted from the HID Barcode Scanner core app. Reads its
 * settings from [TcpConfig] (SharedPreferences) instead of the core's DataStore. Hosted by a
 * foreground [TcpService] so the connection / accept loop survives between scans.
 */
class TcpController(private val context: Context) {
    companion object {
        private const val TAG = "TcpController"

        private const val DEFAULT_PORT = 51000
        private val PORT_RANGE = 1..65535
        private const val READ_BUFFER_SIZE = 1024

        private const val WELCOME_MESSAGE =
            "Hello from Android HID Barcode Scanner TCP plugin"
    }

    private fun L(msg: String) {
        Log.i(TAG, msg)
        EventLog.add(msg)
    }

    private fun LE(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        EventLog.add(if (t == null) msg else failureDetail(msg, t))
    }

    private fun showReceivedMessage(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sendWelcome(socket: Socket) {
        runCatching { socket.getOutputStream().apply { write(WELCOME_MESSAGE.toByteArray()); flush() } }
    }

    private fun failureDetail(prefix: String, throwable: Throwable): String {
        val message = throwable.message
        return if (message.isNullOrBlank()) "$prefix: ${throwable.javaClass.simpleName}" else "$prefix: $message"
    }

    private fun closeServerResources() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        isServerStarted = false
        connectedClients.forEach { runCatching { it.close() } }
        connectedClients.clear()
        isConnected = false
    }

    private fun closeClientResources() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        isConnected = false
    }

    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(controllerJob + Dispatchers.IO)
    private val sendMutex = Mutex()

    private val connectedClients = CopyOnWriteArrayList<Socket>()
    private var activeSocket: Socket? = null
    @Volatile private var isConnected: Boolean = false

    private var serverSocket: ServerSocket? = null
    @Volatile private var isServerStarted: Boolean = false
    private var serverJob: Job? = null
    @Volatile private var serverEpoch = 0
    @Volatile private var serverPort: Int? = null

    private var clientJob: Job? = null
    @Volatile private var clientTarget: String? = null

    private var connectedAddressesCallback: ((TcpStatusData) -> Unit)? = null

    fun setConnectedAddressesCallback(callback: (TcpStatusData) -> Unit) {
        connectedAddressesCallback = callback
    }

    fun isListening(): Boolean {
        if (isConnected) return false
        return (serverJob?.isActive == true && isServerStarted) || clientJob?.isActive == true
    }

    private fun notifyState() {
        val data = when {
            isServerStarted -> serverPort?.let { port ->
                val localAddrs = localIpAddresses()
                val serverAddrs = if (localAddrs.isNotEmpty())
                    localAddrs.map { "$it:$port" }
                else listOf(":$port")
                val clientAddrs = connectedClients.mapNotNull { it.inetAddress?.hostAddress }
                TcpStatusData(serverAddresses = serverAddrs, clientAddresses = clientAddrs)
            } ?: TcpStatusData()
            clientJob?.isActive == true -> {
                val s = activeSocket
                val target = if (isConnected && s != null)
                    s.run { "${inetAddress?.hostAddress ?: inetAddress}:$port" }
                else clientTarget
                TcpStatusData(clientTarget = target)
            }
            else -> TcpStatusData()
        }
        connectedAddressesCallback?.invoke(data)
    }

    fun startServer() {
        if (serverJob?.isActive == true) return
        clientJob?.cancel()
        clientJob = null
        closeClientResources()
        clientTarget = null
        notifyState()

        L("Starting TCP server")
        serverJob = controllerScope.launch {
            val epoch = ++serverEpoch
            var errorCount = 0
            while (isActive) {
                try {
                    val port = TcpConfig.getServerPort(context).coerceIn(PORT_RANGE)
                    val maxClients = TcpConfig.getMaxClients(context).coerceIn(1, 10)
                    val idleTimeoutMs = TcpConfig.getIdleTimeoutMs(context).coerceIn(0, 300_000)

                    if (!isServerStarted || serverSocket == null) {
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress(port))
                        }
                        serverPort = port
                        isServerStarted = true
                        L("TCP server listening on port $port (max $maxClients clients)")
                        notifyState()
                        errorCount = 0
                    }

                    val socket = serverSocket?.accept() ?: continue
                    val clientAddr = socket.inetAddress.hostAddress ?: socket.inetAddress.toString()

                    if (connectedClients.size >= maxClients) {
                        L("TCP max clients ($maxClients) reached, rejecting $clientAddr")
                        runCatching { socket.close() }
                        continue
                    }

                    L("TCP client connected from $clientAddr (${connectedClients.size + 1}/$maxClients)")
                    connectedClients.add(socket)
                    isConnected = true
                    notifyState()

                    launch(Dispatchers.IO) {
                        manageClientConnection(socket, clientAddr, maxClients, idleTimeoutMs)
                    }
                } catch (e: IOException) {
                    if (!isActive) break
                    errorCount++
                    LE("TCP server error (attempt $errorCount)", e)
                    closeServerResources()
                    notifyState()
                    val backoff = minOf(250L * errorCount, 5000L) + Random.nextLong(0, 200)
                    delay(backoff)
                }
            }
            if (epoch == serverEpoch) {
                closeServerResources()
                notifyState()
            }
            L("TCP server loop terminated")
        }
    }

    private fun manageClientConnection(socket: Socket, clientAddr: String, maxClients: Int, idleTimeoutMs: Int) {
        if (idleTimeoutMs > 0) socket.soTimeout = idleTimeoutMs
        val input = socket.getInputStream()
        L("TCP server: connection active from $clientAddr (idle timeout: ${if (idleTimeoutMs > 0) "${idleTimeoutMs}ms" else "disabled"})")
        try {
            sendWelcome(socket)
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val bytes = input.read(buffer)
                if (bytes == -1) break
                val received = String(buffer, 0, bytes)
                L("TCP server received from $clientAddr: $received")
                showReceivedMessage(received)
            }
        } catch (e: SocketTimeoutException) {
            L("TCP server: $clientAddr idle timeout after ${idleTimeoutMs}ms — disconnecting")
        } catch (e: IOException) {
            LE("TCP server: connection error from $clientAddr", e)
        } finally {
            runCatching { socket.close() }
            connectedClients.remove(socket)
            isConnected = connectedClients.isNotEmpty()
            notifyState()
            L("TCP server: $clientAddr disconnected (${connectedClients.size}/$maxClients remaining)")
        }
    }

    fun startClient() {
        if (clientJob?.isActive == true) return
        serverJob?.cancel()
        serverJob = null
        closeServerResources()
        serverPort = null
        notifyState()

        L("Starting TCP client")
        clientJob = controllerScope.launch {
            var errorCount = 0
            while (isActive) {
                var socket: Socket? = null
                var connectionEndedAfterConnect = false
                try {
                    val host = TcpConfig.getHost(context)
                    val port = TcpConfig.getClientPort(context).coerceIn(PORT_RANGE)
                    val connectTimeoutMs = TcpConfig.getConnectTimeoutMs(context).coerceIn(500, 30_000)

                    if (host.isBlank()) {
                        L("TCP client: no host configured, waiting...")
                        clientTarget = null
                        notifyState()
                        delay(5000)
                        continue
                    }

                    clientTarget = "$host:$port"
                    L("TCP client connecting to $host:$port")
                    notifyState()

                    socket = Socket()
                    activeSocket = socket
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                    L("TCP client connected to $host:$port")

                    isConnected = true
                    errorCount = 0
                    notifyState()

                    withContext(Dispatchers.IO) {
                        manageConnection(socket, "client")
                    }
                    connectionEndedAfterConnect = true
                } catch (e: IOException) {
                    errorCount++
                    LE("TCP client connect error (attempt $errorCount)", e)
                    val backoff = minOf(250L * errorCount, 5000L) + Random.nextLong(0, 200)
                    delay(backoff)
                } finally {
                    isConnected = false
                    if (activeSocket === socket) {
                        runCatching { socket?.close() }
                        activeSocket = null
                    }
                    notifyState()
                }

                if (connectionEndedAfterConnect && isActive) {
                    val backoff = 1000L + Random.nextLong(0, 200)
                    L("TCP client disconnected — reconnecting in ${backoff}ms")
                    delay(backoff)
                }
            }
            L("TCP client loop terminated")
        }
    }

    fun restartServer() {
        serverJob?.cancel()
        serverJob = null
        closeServerResources()
        notifyState()
        startServer()
    }

    fun restartClient() {
        clientJob?.cancel()
        clientJob = null
        closeClientResources()
        notifyState()
        startClient()
    }

    fun stop() {
        L("Stopping TCP controller")
        serverJob?.cancel()
        serverJob = null
        clientJob?.cancel()
        clientJob = null
        closeClientResources()
        closeServerResources()
        serverPort = null
        clientTarget = null
        notifyState()
        controllerJob.cancel()
        L("TCP controller stopped")
    }

    private fun manageConnection(socket: Socket, role: String) {
        val input = socket.getInputStream()
        L("TCP $role: connection active")
        try {
            sendWelcome(socket)
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val bytes = input.read(buffer)
                if (bytes == -1) break
                val received = String(buffer, 0, bytes)
                L("TCP $role received: $received")
                showReceivedMessage(received)
            }
        } catch (e: IOException) {
            LE("TCP $role: connection error", e)
        } finally {
            runCatching { socket.close() }
            L("TCP $role: connection terminated")
        }
    }

    fun sendProcessedData(processedString: String, onResult: (Boolean, String) -> Unit) {
        controllerScope.launch {
            sendMutex.withLock {
                val data = processedString.toByteArray(Charsets.UTF_8)

                // Server mode: broadcast to all connected clients.
                if (serverJob?.isActive == true) {
                    val recipients = connectedClients.toList()
                    if (recipients.isEmpty()) {
                        L("TCP server: no clients connected — data dropped")
                        onResult(false, "no TCP clients connected")
                        return@withLock
                    }
                    var delivered = 0
                    recipients.forEach { socket ->
                        runCatching {
                            val out = socket.getOutputStream()
                            out.write(data)
                            out.flush()
                        }.onSuccess { delivered++ }.onFailure { e ->
                            LE("TCP broadcast send failed to ${socket.inetAddress.hostAddress ?: socket.inetAddress}", e)
                            runCatching { socket.close() }
                            connectedClients.remove(socket)
                            isConnected = connectedClients.isNotEmpty()
                            notifyState()
                        }
                    }
                    L("TCP broadcast to $delivered/${recipients.size} client(s): $processedString")
                    onResult(
                        delivered > 0,
                        if (delivered > 0) "forwarded to $delivered/${recipients.size} TCP client(s)"
                        else "TCP broadcast failed for all ${recipients.size} client(s)"
                    )
                    return@withLock
                }

                // Client mode: single socket. On no connection, reconnect and retry once.
                val socket = activeSocket
                if (socket == null || !isConnected) {
                    L("TCP client: no active connection — triggering reconnect")
                    restartClient()
                    val connectTimeoutMs = TcpConfig.getConnectTimeoutMs(context).coerceIn(500, 30_000)
                    val deadline = System.currentTimeMillis() + connectTimeoutMs + 300
                    while (!isConnected && isActive && System.currentTimeMillis() < deadline) {
                        delay(50)
                    }
                    val retry = activeSocket
                    if (retry != null && isConnected) {
                        runCatching {
                            val out = retry.getOutputStream()
                            out.write(data)
                            out.flush()
                            L("TCP sent after reconnect: $processedString")
                        }.onSuccess {
                            onResult(true, "forwarded over TCP after reconnect")
                        }.onFailure {
                            LE("TCP retry send failed", it)
                            onResult(false, failureDetail("TCP retry send failed", it))
                        }
                    } else {
                        L("TCP reconnect didn't establish in time — data dropped")
                        onResult(false, "TCP reconnect did not establish in time")
                    }
                    return@withLock
                }

                runCatching {
                    val out = socket.getOutputStream()
                    out.write(data)
                    out.flush()
                    L("TCP sent: $processedString")
                }.onSuccess {
                    onResult(true, "forwarded over TCP")
                }.onFailure { e ->
                    LE("TCP send failed", e)
                    restartClient()
                    onResult(false, failureDetail("TCP send failed", e))
                }
            }
        }
    }
}
