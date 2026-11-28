package be.mygod.vpnhotspot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.mygod.vpnhotspot.xray.XrayServer

@Composable
private fun Pick(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value.ifEmpty { "none" }, Modifier.weight(1f))
                Text("v")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o.ifEmpty { "none" }) }, onClick = { onPick(o); open = false }) }
            }
        }
    }
}

/** Edit screen for one saved server. Changes apply the next time you connect. */
@Composable
fun ServerEditor(start: XrayServer, inUse: Boolean, onClose: () -> Unit, onSave: (XrayServer) -> Unit) {
    var s by remember { mutableStateOf(start) }
    var port by remember { mutableStateOf(start.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    @Composable fun field(label: String, v: String, set: (String) -> Unit, mod: Modifier = Modifier.fillMaxWidth()) =
        OutlinedTextField(value = v, onValueChange = set, label = { Text(label) }, modifier = mod, singleLine = true)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Close") }
                    Text("Edit server (${s.protocol})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        val p = port.trim().toIntOrNull()
                        val msg = when {
                            s.host.isBlank() -> "Address is empty"
                            p == null || p !in 1..65535 -> "Port must be 1 to 65535"
                            s.id.isBlank() -> "User ID is empty"
                            else -> null
                        }
                        if (msg != null) { error = msg; return@Button }
                        val out = s.copy(port = p!!, host = s.host.trim(), hostName = "", id = s.id.trim(), name = s.name.ifBlank { s.host })
                        if (XrayServer.parse(out.toLink()) == null) error = "These values do not make a valid link" else onSave(out)
                    }) { Text("Save") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (inUse) Text("This server is connected. Changes apply the next time you connect.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    field("Remarks", s.name, { s = s.copy(name = it) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field("Address", s.host, { s = s.copy(host = it) }, Modifier.weight(2f))
                        field("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f))
                    }
                    field("User ID", s.id, { s = s.copy(id = it) })
                    if (s.protocol == "vmess") {
                        Pick("Encrypt method", s.vmessCipher, listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) { s = s.copy(vmessCipher = it) }
                        field("Alter ID", s.alterId.toString(), { s = s.copy(alterId = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) })
                    } else {
                        Pick("Flow", s.flow, listOf("", "xtls-rprx-vision")) { s = s.copy(flow = it) }
                    }
                    Pick("Transfer protocol", s.network, listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) { s = s.copy(network = it) }
                    if (s.network in listOf("ws", "httpupgrade", "xhttp")) field("Host", s.wsHost, { s = s.copy(wsHost = it) })
                    if (s.network != "tcp") field(if (s.network == "grpc") "Service name" else "Path (for example /server_path)", s.path, { s = s.copy(path = it) })
                    Pick("TLS type", s.security, listOf("none", "tls", "reality")) { s = s.copy(security = it) }
                    if (s.security != "none") {
                        field("Server Name Indication (SNI)", s.sni, { s = s.copy(sni = it) })
                        Pick("Fingerprint", s.fingerprint, listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized")) { s = s.copy(fingerprint = it) }
                        Pick("Alpn", s.alpn, listOf("", "h2", "http/1.1", "h2,http/1.1")) { s = s.copy(alpn = it) }
                    }
                    if (s.security == "reality") {
                        field("Public key", s.publicKey, { s = s.copy(publicKey = it) })
                        field("Short ID", s.shortId, { s = s.copy(shortId = it) })
                    }
                    Row(Modifier.clickable { s = s.copy(mux = !s.mux) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s.mux, onCheckedChange = null)
                        Text("Multiplexing (Mux)")
                    }
                    Text("Leave SNI and Host empty to use the server address. Values you type here are used exactly as written.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
