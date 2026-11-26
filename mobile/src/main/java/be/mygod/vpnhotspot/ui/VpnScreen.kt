package be.mygod.vpnhotspot.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import be.mygod.vpnhotspot.xray.XrayController
import be.mygod.vpnhotspot.xray.XrayServer
import kotlinx.coroutines.launch

/** VPN tab: paste a vless:// or vmess:// link, pick a server, connect. [hotspotIfaces] are the active hotspot interfaces. */
@Composable
fun VpnScreen(hotspotIfaces: Collection<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var links by remember { mutableStateOf(XrayController.savedLinks()) }
    var selected by remember { mutableIntStateOf(XrayController.selected) }
    val running by XrayController.running.collectAsState()
    val status by XrayController.message.collectAsState()
    var logText by remember { mutableStateOf(XrayController.crashLog() + XrayController.readLog()) }
    LaunchedEffect(Unit) { XrayController.installCrashLog(); XrayController.refresh(); XrayController.detectRoot() }
    val root by XrayController.rootAvailable.collectAsState()
    var mode by remember { mutableStateOf(XrayController.mode) }
    val proxyMode = mode == "proxy" || (mode == "auto" && root != true)
    var addresses by remember { mutableStateOf(XrayController.hotspotAddresses()) }
    val servers = links.map { XrayServer.parse(it) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = text, onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Paste vless:// or vmess:// link") },
                isError = error != null, supportingText = error?.let { { Text(it) } },
                minLines = 2, maxLines = 5,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                }) { Text("Paste") }
                Button(onClick = {
                    val found = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val valid = found.filter { XrayServer.parse(it) != null }
                    if (valid.isEmpty()) error = "No valid vless:// or vmess:// link found" else {
                        links = (links + valid).distinct()
                        XrayController.saveLinks(links)
                        text = ""
                    }
                }) { Text("Add") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (running) "VPN is on" else "VPN is off", style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        Text(if (proxyMode) "Proxy mode (no root): devices on your hotspot set the proxy below in their Wi-Fi settings. Only apps that follow the Wi-Fi proxy are covered (browsers do, many apps and games do not)."
                        else "Transparent mode (root): hotspot TCP, DNS and UDP go through the server with no settings on the other device. UDP needs kernel TPROXY support.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = running || servers.getOrNull(selected) != null, onClick = {
                        scope.launch {
                            try {
                                if (running) XrayController.stop()
                                else servers.getOrNull(selected)?.let {
                                    if (proxyMode) XrayController.startProxy(it) else XrayController.start(it, hotspotIfaces)
                                }
                                addresses = XrayController.hotspotAddresses()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                XrayController.appendLog("UI: " + e.stackTraceToString())
                            }
                            logText = XrayController.crashLog() + XrayController.readLog()
                        }
                    }) { Text(if (running) "Disconnect" else "Connect") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Auto", "transparent" to "Root", "proxy" to "Proxy").forEach { (key, label) ->
                            FilterChip(selected = mode == key, enabled = !running, onClick = {
                                mode = key; XrayController.mode = key
                            }, label = { Text(label) })
                        }
                    }
                    Text(when (root) {
                        null -> "Checking for root..."
                        true -> "Root found. Auto uses transparent mode."
                        false -> "No root found. Auto uses proxy mode."
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (proxyMode) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Proxy address for other devices", style = MaterialTheme.typography.titleSmall)
                    if (addresses.isEmpty()) Text("No hotspot address found. Turn on the hotspot in Android settings, then tap Refresh.",
                        style = MaterialTheme.typography.bodySmall)
                    addresses.forEach { ip ->
                        listOf("HTTP (Wi-Fi proxy)" to XrayController.HTTP_PORT, "SOCKS5" to XrayController.SOCKS_PORT).forEach { (name, port) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$name  $ip:$port", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Routify proxy", "$ip:$port"))
                                }) { Text("Copy") }
                            }
                        }
                    }
                    Text("On the other device: Wi-Fi > this hotspot > Proxy > Manual > enter the HTTP address and port.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { addresses = XrayController.hotspotAddresses() }) { Text("Refresh") }
                }
            }
        }
        if (logText.isNotBlank()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Error log (ids and links are hidden)", style = MaterialTheme.typography.titleSmall)
                    Text(logText.takeLast(1500), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Routify log", logText))
                        }) { Text("Copy") }
                        TextButton(onClick = { XrayController.clearLog(); logText = "" }) { Text("Clear") }
                    }
                }
            }
        }
        itemsIndexed(links) { i, link ->
            val s = servers[i]
            Card(Modifier.fillMaxWidth().clickable { selected = i; XrayController.selected = i }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = i == selected, onClick = { selected = i; XrayController.selected = i })
                    Column(Modifier.weight(1f)) {
                        Text(s?.name ?: "Invalid link", style = MaterialTheme.typography.bodyLarge)
                        if (s != null) Text("${s.protocol} ${s.network}/${s.security} ${s.host}:${s.port}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        links = links.filterIndexed { j, _ -> j != i }
                        XrayController.saveLinks(links)
                        if (i < selected) selected -= 1
                        if (selected >= links.size) selected = 0
                        XrayController.selected = selected
                    }) { Text("Remove") }
                }
            }
        }
    }
}

