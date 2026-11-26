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
    LaunchedEffect(Unit) { XrayController.installCrashLog(); XrayController.refresh() }
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
                        Text("Hotspot TCP and DNS go through the server. Other UDP is not proxied yet.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = running || servers.getOrNull(selected) != null, onClick = {
                        scope.launch {
                            try {
                                if (running) XrayController.stop()
                                else servers.getOrNull(selected)?.let { XrayController.start(it, hotspotIfaces) }
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
                        if (selected >= links.size) selected = 0
                    }) { Text("Remove") }
                }
            }
        }
    }
}

