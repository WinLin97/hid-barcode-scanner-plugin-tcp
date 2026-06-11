package dev.zselog.bluetoothhid.plugin.tcp

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ enforces edge-to-edge for this targetSdk; this call brings the same
        // transparent system bars to older devices so the nav bar follows the app theme there too.
        enableEdgeToEdge()
        // The frames before Compose's first draw (splash → XML-theme window) use the SYSTEM
        // dark-mode setting, so an in-app theme override used to flash the wrong colors twice on
        // every launch. Paint the window and the system-bar icon contrast to the persisted choice
        // up front; Compose then first-draws in the same colors and nothing visibly changes.
        val dark = TcpConfig.getDarkTheme(this)
            ?: (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES)
        window.setBackgroundDrawable(
            ColorDrawable(if (dark) 0xFF1C1B1F.toInt() else 0xFFFFFBFE.toInt())
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        setContent { PluginApp() }
        // Diagnostic snapshot every time the settings UI opens — lands in the Event log tab, so
        // "why doesn't it work" (lost permission, no core app, no network…) is always answerable.
        SelfCheck.log(this)
    }
}

private const val CORE_REPO = "https://github.com/Fabi019/hid-barcode-scanner"
private const val PLUGIN_REPO = "https://github.com/WinLin97/hid-barcode-scanner-plugin-tcp"

/** Root: owns the persisted theme choice so toggling it recomposes the whole color scheme. */
@Composable
private fun PluginApp() {
    val context = LocalContext.current
    var darkOverride by remember { mutableStateOf(TcpConfig.getDarkTheme(context)) } // null = system
    var dynamic by remember { mutableStateOf(TcpConfig.getDynamicTheme(context)) }

    val dark = darkOverride ?: isSystemInDarkTheme()
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    // System bar icon contrast must follow the IN-APP theme choice, not the XML theme: the user
    // can override dark/light from the menu, so windowLightStatusBar from styles.xml would go
    // stale. Light theme → dark icons, dark theme → light icons.
    val view = LocalView.current
    val applyBarAppearance: (Boolean) -> Unit = { isDark ->
        if (!view.isInEditMode) {
            val window = (view.context as ComponentActivity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            // 3-button navigation: the system paints a fixed light scrim behind the buttons by
            // default, which clashes with the dark theme. Disable it so the buttons sit directly
            // on the app background (icon contrast is handled above).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    SideEffect { applyBarAppearance(dark) }

    MaterialTheme(colorScheme = colors) {
        SettingsScreen(
            dark = dark,
            dynamic = dynamic,
            onToggleDark = {
                val next = !dark
                darkOverride = next
                TcpConfig.setDarkTheme(context, next)
                // Pre-frame flip so the system-bar icons recolor with the same vsync as the app
                // content instead of one frame after it (SideEffect alone runs post-draw).
                applyBarAppearance(next)
            },
            onToggleDynamic = {
                val next = !dynamic
                dynamic = next
                TcpConfig.setDynamicTheme(context, next)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    dark: Boolean,
    dynamic: Boolean,
    onToggleDark: () -> Unit,
    onToggleDynamic: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            // key(dark, dynamic): M3's TopAppBar animates its container color with a ~0.5s spring
            // (the scroll-transition machinery), which also kicks in on a theme switch — the rest
            // of the UI snaps in one frame while the bar slowly cross-fades. Recreating it on
            // theme change resets that animation state so the bar recolors in the same frame.
            key(dark, dynamic) {
                TopAppBar(
                    title = { Text(stringResource(R.string.screen_title)) },
                    actions = { ThemeMenu(dark, dynamic, onToggleDark, onToggleDynamic) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                listOf(
                    stringResource(R.string.tab_transport),
                    stringResource(R.string.tab_event_log),
                ).forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) }
                    )
                }
            }
            // beyondViewportPageCount keeps the off-screen tab composed so unsaved form edits
            // survive swiping to the log and back.
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> TransportTab()
                    else -> LogTab()
                }
            }
        }
    }
}

@Composable
private fun ThemeMenu(
    dark: Boolean,
    dynamic: Boolean,
    onToggleDark: () -> Unit,
    onToggleDynamic: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
    }
    // Dismiss BEFORE toggling: the popup is its own window and repaints a frame later than the
    // app window, so changing the theme with it open makes the top of the screen visibly flash
    // twice. Closing first leaves a single, clean recolor (and matches stock menu behaviour).
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.dark_theme)) },
            trailingIcon = { Checkbox(checked = dark, onCheckedChange = null) },
            onClick = {
                expanded = false
                onToggleDark()
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dynamic_theme)) },
                trailingIcon = { Checkbox(checked = dynamic, onCheckedChange = null) },
                onClick = {
                    expanded = false
                    onToggleDynamic()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransportTab() {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(TcpConfig.getMode(context)) }
    var host by remember { mutableStateOf(TcpConfig.getHost(context)) }
    var clientPort by remember { mutableStateOf(TcpConfig.getClientPort(context).toString()) }
    var connectTimeout by remember { mutableStateOf(TcpConfig.getConnectTimeoutMs(context).toString()) }
    var serverPort by remember { mutableStateOf(TcpConfig.getServerPort(context).toString()) }
    var maxClients by remember { mutableStateOf(TcpConfig.getMaxClients(context).toString()) }
    var idleTimeout by remember { mutableStateOf(TcpConfig.getIdleTimeoutMs(context).toString()) }

    // Every field is validated against the same ranges the controller enforces at runtime —
    // no silent default-substitution or hidden coercion between what the form shows and what runs.
    val hostValid = host.isNotBlank()
    val clientPortValid = clientPort.toIntOrNull()?.let { it in 1..65535 } ?: false
    val serverPortValid = serverPort.toIntOrNull()?.let { it in 1..65535 } ?: false
    val connectTimeoutValid = connectTimeout.toIntOrNull()?.let { it in 500..30_000 } ?: false
    val maxClientsValid = maxClients.toIntOrNull()?.let { it in 1..10 } ?: false
    val idleTimeoutValid = idleTimeout.toIntOrNull()?.let { it in 0..300_000 } ?: false
    val formValid = when (mode) {
        TcpConfig.Mode.CLIENT -> hostValid && clientPortValid && connectTimeoutValid
        TcpConfig.Mode.SERVER -> serverPortValid && maxClientsValid && idleTimeoutValid
    }

    // Persist whatever the form currently shows. Both Save and Start go through this so the visible
    // form is always the single source of truth — no "changed mode but service still on the old one".
    // Only reachable with formValid == true; the defaults are a parse-failure backstop, not a policy.
    fun persistForm() = TcpConfig.save(
        context,
        mode = mode,
        host = host,
        clientPort = clientPort.toIntOrNull() ?: TcpConfig.DEFAULT_PORT,
        connectTimeoutMs = connectTimeout.toIntOrNull() ?: TcpConfig.DEFAULT_CONNECT_TIMEOUT_MS,
        serverPort = serverPort.toIntOrNull() ?: TcpConfig.DEFAULT_PORT,
        maxClients = maxClients.toIntOrNull() ?: TcpConfig.DEFAULT_MAX_CLIENTS,
        idleTimeoutMs = idleTimeout.toIntOrNull() ?: TcpConfig.DEFAULT_IDLE_TIMEOUT_MS,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Live transport status — the actual running state, independent of the unsaved form above.
        LiveStatusCard()

        Text(
            stringResource(R.string.intro),
            style = MaterialTheme.typography.bodyMedium
        )

        SectionTitle(stringResource(R.string.mode))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == TcpConfig.Mode.CLIENT,
                onClick = { mode = TcpConfig.Mode.CLIENT },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text(stringResource(R.string.mode_client)) }
            SegmentedButton(
                selected = mode == TcpConfig.Mode.SERVER,
                onClick = { mode = TcpConfig.Mode.SERVER },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text(stringResource(R.string.mode_server)) }
        }

        // Show only the fields relevant to the selected mode
        val portError = stringResource(R.string.port_range_error)
        if (mode == TcpConfig.Mode.CLIENT) {
            SectionTitle(stringResource(R.string.section_client))
            Field(
                host, { host = it }, stringResource(R.string.host), numeric = false,
                isError = !hostValid,
                errorText = if (!hostValid) stringResource(R.string.host_required) else null,
            )
            Field(
                clientPort, { clientPort = it }, stringResource(R.string.client_port),
                isError = !clientPortValid,
                errorText = if (!clientPortValid) portError else null,
            )
            Field(
                connectTimeout, { connectTimeout = it }, stringResource(R.string.connect_timeout),
                isError = !connectTimeoutValid,
                errorText = if (!connectTimeoutValid) stringResource(R.string.connect_timeout_range_error) else null,
            )
        } else {
            SectionTitle(stringResource(R.string.section_server))
            ServerAddresses(portText = serverPort)
            Field(
                serverPort, { serverPort = it }, stringResource(R.string.server_port),
                isError = !serverPortValid,
                errorText = if (!serverPortValid) portError else null,
            )
            Field(
                maxClients, { maxClients = it }, stringResource(R.string.max_clients),
                isError = !maxClientsValid,
                errorText = if (!maxClientsValid) stringResource(R.string.max_clients_range_error) else null,
            )
            Field(
                idleTimeout, { idleTimeout = it }, stringResource(R.string.idle_timeout),
                isError = !idleTimeoutValid,
                errorText = if (!idleTimeoutValid) stringResource(R.string.idle_timeout_range_error) else null,
            )
        }

        Spacer(Modifier.height(4.dp))
        val savedToast = stringResource(R.string.saved_and_restarted)
        Button(
            onClick = {
                persistForm()
                EventLog.add("Settings saved: ${mode.name.lowercase()}")
                TcpService.restart(context)
                Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
            },
            enabled = formValid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.save_settings))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    // Persist first so Start always runs the mode/ports currently shown.
                    persistForm()
                    TcpService.start(context)
                },
                // Same gate as Save — Start must never run a config the form rejects.
                enabled = formValid,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start))
            }
            OutlinedButton(onClick = { TcpService.stop(context) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stop))
            }
        }

        SectionTitle(stringResource(R.string.about))
        Text(
            stringResource(R.string.version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium
        )
        RepoLink(stringResource(R.string.core_repo_link)) { context.openUrl(CORE_REPO) }
        RepoLink(stringResource(R.string.plugin_repo_link)) { context.openUrl(PLUGIN_REPO) }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * All IPv4 addresses a TCP client could connect to: every up interface (Wi-Fi, Ethernet, VPN,
 * mobile data) INCLUDING loopback — the server binds the wildcard address, so a client on the
 * same device can use 127.0.0.1. Loopback is sorted last; the externally reachable ones matter more.
 */
private fun localIpv4Addresses(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .sortedBy { it.startsWith("127.") }
    }.getOrDefault(emptyList())

/**
 * Server mode helper: lists the device addresses clients can connect to, with the port currently
 * typed in the form. Re-polled every few seconds so joining/leaving a network updates the list
 * while the screen is open (the poll dies with the composable).
 */
@Composable
private fun ServerAddresses(portText: String) {
    val addresses by produceState(initialValue = localIpv4Addresses()) {
        while (true) {
            delay(3_000)
            value = localIpv4Addresses()
        }
    }
    if (addresses.isNotEmpty()) {
        Text(
            stringResource(R.string.reachable_at),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        addresses.forEach { ip ->
            Text(
                "$ip:$portText",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    // Only loopback (or nothing at all) → other devices can't reach us; explain why.
    if (addresses.none { !it.startsWith("127.") }) {
        Text(
            stringResource(R.string.no_network),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Shows the real, live transport state (running/connected/waiting/stopped) from [TcpService]. */
@Composable
private fun LiveStatusCard() {
    val status by TcpService.status.collectAsState()
    val (color, label) = when {
        !status.running ->
            MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.status_stopped)
        status.connected ->
            Color(0xFF43A047) to (status.summary ?: stringResource(R.string.status_connected))
        else ->
            Color(0xFFFB8C00) to (status.summary ?: stringResource(R.string.status_waiting))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp)
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LogTab() {
    val logEntries by EventLog.entries.collectAsState()
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { EventLog.clear() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.clear_log))
        }
        if (logEntries.isEmpty()) {
            Text(stringResource(R.string.no_events), style = MaterialTheme.typography.bodySmall)
        } else {
            logEntries.forEach { entry ->
                Text(entry, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    HorizontalDivider()
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    numeric: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (isError && errorText != null) ({ Text(errorText) }) else null,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number)
        else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RepoLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(vertical = 4.dp)) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

// runCatching: devices without any browser (kiosk / work profile) would otherwise crash here.
private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
