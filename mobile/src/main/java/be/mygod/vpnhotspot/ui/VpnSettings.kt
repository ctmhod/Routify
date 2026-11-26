package be.mygod.vpnhotspot.ui

import androidx.compose.foundation.layout.Column
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

