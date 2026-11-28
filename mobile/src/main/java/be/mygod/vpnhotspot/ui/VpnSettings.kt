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
import androidx.compose.ui.text.AnnotatedString
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
    var sel by remember { mutableStateOf(false) }
    val names = listOf("Other devices only", "This device and other devices")
    PreferenceRow(
        icon = R.drawable.ic_route,
        title = "What Connect covers",
        summary = names[if (phone) 1 else 0] + ". Applies next time you connect.",
        onClick = { sel = true },
    )
    if (sel) PreferenceSelectionSheet(
        title = "What Connect covers",
        entryCount = 2,
        selectedIndex = if (phone) 1 else 0,
        entryLabel = names::get,
        entrySummary = { if (it == 1) AnnotatedString("Also puts this phone or tablet on the VPN, like a normal VPN app. Android asks for permission the first time.") else null },
        onDismissRequest = { sel = false },
        onSelect = { XrayController.phoneVpn = it == 1; phone = it == 1 },
    )
}

@Composable
fun VpnFailRow() {
    var block by remember { mutableStateOf(XrayController.blockOnFail) }
    var sel by remember { mutableStateOf(false) }
    val names = listOf("Use the normal connection", "Block until it works")
    PreferenceRow(
        icon = R.drawable.ic_route,
        title = "If the server stops working",
        summary = names[if (block) 1 else 0] + ". Applies next time you connect.",
        onClick = { sel = true },
    )
    if (sel) PreferenceSelectionSheet(
        title = "If the server stops working",
        entryCount = 2,
        selectedIndex = if (block) 1 else 0,
        entryLabel = names::get,
        entrySummary = { AnnotatedString(if (it == 0) "Other devices keep working, but without the VPN, while the server does not answer." else "Other devices have no internet until the server answers.") },
        onDismissRequest = { sel = false },
        onSelect = { XrayController.blockOnFail = it == 1; block = it == 1 },
    )
}

@Composable
fun VpnLogRow() {
    var level by remember { mutableStateOf(XrayController.logLevel) }
    var sel by remember { mutableStateOf(false) }
    val levels = XrayController.LOG_LEVELS.toList()
    PreferenceRow(
        icon = R.drawable.ic_bug_report,
        title = "VPN log detail",
        summary = "$level. Use debug only when asked, then share the log.",
        onClick = { sel = true },
    )
    if (sel) PreferenceSelectionSheet(
        title = "VPN log detail",
        entryCount = levels.size,
        selectedIndex = levels.indexOf(level).coerceAtLeast(0),
        entryLabel = levels::get,
        onDismissRequest = { sel = false },
        onSelect = { XrayController.logLevel = levels[it]; level = levels[it] },
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

