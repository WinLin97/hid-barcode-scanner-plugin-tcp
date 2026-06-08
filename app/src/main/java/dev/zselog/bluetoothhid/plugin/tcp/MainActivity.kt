package dev.zselog.bluetoothhid.plugin.tcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { SettingsScreen() }
        }
    }
}

private const val CORE_REPO = "https://github.com/Fabi019/hid-barcode-scanner"
private const val PLUGIN_REPO = "https://github.com/WinLin97/hid-barcode-scanner-plugin-tcp"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(TcpConfig.getMode(context)) }
    var host by remember { mutableStateOf(TcpConfig.getHost(context)) }
    var clientPort by remember { mutableStateOf(TcpConfig.getClientPort(context).toString()) }
    var connectTimeout by remember { mutableStateOf(TcpConfig.getConnectTimeoutMs(context).toString()) }
    var serverPort by remember { mutableStateOf(TcpConfig.getServerPort(context).toString()) }
    var maxClients by remember { mutableStateOf(TcpConfig.getMaxClients(context).toString()) }
    var idleTimeout by remember { mutableStateOf(TcpConfig.getIdleTimeoutMs(context).toString()) }
    val logEntries by EventLog.entries.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("TCP Transport Plugin") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Scans from the HID Barcode Scanner are forwarded over TCP. Enable this plugin in " +
                    "the scanner: Settings → Connection → External → toggle it on.",
                style = MaterialTheme.typography.bodyMedium
            )

            SectionTitle("Mode")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == TcpConfig.Mode.CLIENT,
                    onClick = { mode = TcpConfig.Mode.CLIENT },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Client") }
                SegmentedButton(
                    selected = mode == TcpConfig.Mode.SERVER,
                    onClick = { mode = TcpConfig.Mode.SERVER },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Server") }
            }

            // Show only the fields relevant to the selected mode
            if (mode == TcpConfig.Mode.CLIENT) {
                SectionTitle("Client (connect to a server)")
                Field(host, { host = it }, "Host", numeric = false)
                Field(clientPort, { clientPort = it }, "Client port")
                Field(connectTimeout, { connectTimeout = it }, "Connect timeout (ms)")
            } else {
                SectionTitle("Server (listen for clients)")
                Field(serverPort, { serverPort = it }, "Server port")
                Field(maxClients, { maxClients = it }, "Max clients")
                Field(idleTimeout, { idleTimeout = it }, "Client idle timeout (ms, 0 = off)")
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    TcpConfig.save(
                        context,
                        mode = mode,
                        host = host,
                        clientPort = clientPort.toIntOrNull() ?: TcpConfig.DEFAULT_PORT,
                        connectTimeoutMs = connectTimeout.toIntOrNull() ?: TcpConfig.DEFAULT_CONNECT_TIMEOUT_MS,
                        serverPort = serverPort.toIntOrNull() ?: TcpConfig.DEFAULT_PORT,
                        maxClients = maxClients.toIntOrNull() ?: TcpConfig.DEFAULT_MAX_CLIENTS,
                        idleTimeoutMs = idleTimeout.toIntOrNull() ?: TcpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                    )
                    EventLog.add("Settings saved: ${mode.name.lowercase()}")
                    TcpService.restart(context)
                    Toast.makeText(context, "Saved and restarted", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("Save settings")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { TcpService.start(context) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start")
                }
                OutlinedButton(onClick = { TcpService.stop(context) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            }

            SectionTitle("Event log")
            OutlinedButton(onClick = { EventLog.clear() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(8.dp))
                Text("Clear log")
            }
            if (logEntries.isEmpty()) {
                Text("No events yet", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logEntries.forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionTitle("About")
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            RepoLink("Core app (HID Barcode Scanner)") { context.openUrl(CORE_REPO) }
            RepoLink("This plugin's repository") { context.openUrl(PLUGIN_REPO) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
    HorizontalDivider()
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
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

private fun Context.openUrl(url: String) =
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
