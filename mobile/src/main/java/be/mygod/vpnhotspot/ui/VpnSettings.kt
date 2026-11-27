package be.mygod.vpnhotspot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import be.mygod.vpnhotspot.util.AppLog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.xray.XrayController

/** Routify: VPN options shown on the Settings page. Both apply the next time you press Connect. */
@Composable
fun VpnDnsRow() {
    var dns by remember { mutableStateOf(XrayController.dnsServer) }
    var editDns by remember { mutableStateOf(false) }
    PreferenceRow(
        icon = R.drawable.ic_route,
        title = "VPN DNS server",
        summary = "$dns (asked through the VPN). Applies next time you connect.",
        onClick = { editDns = true },
    )
    if (editDns) {
        var draft by remember { mutableStateOf(dns) }
        val ok = XrayController.isIpv4(draft.trim())
        AlertDialog(
            onDismissRequest = { editDns = false },
            title = { Text("VPN DNS server") },
            text = {
                Column {
                    Text("An IPv4 address, for example 1.1.1.1 or 8.8.8.8.")
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, singleLine = true,
                        isError = !ok, label = { Text("DNS server") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    XrayController.dnsServer = draft.trim(); dns = XrayController.dnsServer; editDns = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editDns = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun VpnCoverageRow() {
    var phone by remember { mutableStateOf(XrayController.phoneVpn) }
    var edit by remember { mutableStateOf(false) }
    PreferenceRow(
        icon = R.drawable.ic_route,
        title = "What Connect covers",
        summary = (if (phone) "This device and other devices" else "Other devices only") + ". Applies next time you connect.",
        onClick = { edit = true },
    )
    if (edit) AlertDialog(
        onDismissRequest = { edit = false },
        title = { Text("What Connect covers") },
        text = {
            Column {
                TextButton(onClick = { XrayController.phoneVpn = false; phone = false; edit = false }) {
                    Text(if (!phone) "Other devices only (selected)" else "Other devices only")
                }
                TextButton(onClick = { XrayController.phoneVpn = true; phone = true; edit = false }) {
                    Text(if (phone) "This device and other devices (selected)" else "This device and other devices")
                }
                Text("The second option also puts this phone or tablet on the VPN, like a normal VPN app. Android asks for permission the first time.")
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } },
    )
}

@Composable
fun VpnLogRow() {
    var level by remember { mutableStateOf(XrayController.logLevel) }
    var editLevel by remember { mutableStateOf(false) }
    PreferenceRow(
        icon = R.drawable.ic_bug_report,
        title = "VPN log detail",
        summary = "$level. Use debug only when asked, then share the log.",
        onClick = { editLevel = true },
    )
    if (editLevel) AlertDialog(
        onDismissRequest = { editLevel = false },
        title = { Text("VPN log detail") },
        text = {
            Column {
                for (l in XrayController.LOG_LEVELS) TextButton(onClick = {
                    XrayController.logLevel = l; level = l; editLevel = false
                }) { Text(if (l == level) "$l (selected)" else l) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { editLevel = false }) { Text("Cancel") } },
    )
}

/** Settings row that opens the app-wide error log (Copy and Clear). */
@Composable
fun ErrorLogRow() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    PreferenceRow(
        icon = R.drawable.ic_bug_report,
        title = "Error log",
        summary = "Warnings, errors and crashes from the whole app. Personal data is hidden.",
        onClick = { text = AppLog.read(); open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Error log") },
        text = {
            Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                Text(if (text.isBlank()) "Nothing logged." else text.takeLast(6000))
            }
        },
        confirmButton = {
            Column {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Routify log", text))
                }) { Text("Copy") }
                TextButton(onClick = { AppLog.clear(); text = "" }) { Text("Clear") }
            }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}

