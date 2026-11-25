package be.mygod.vpnhotspot.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.limits.Defaults
import be.mygod.vpnhotspot.limits.LimitsEnforcer
import be.mygod.vpnhotspot.limits.LimitsStore
import be.mygod.vpnhotspot.limits.OverAction
import be.mygod.vpnhotspot.limits.LimitOverride
import be.mygod.vpnhotspot.limits.ResetRule
import kotlinx.coroutines.launch

@Composable
private fun <T : Any> ChoiceRow(
    title: String,
    options: List<Pair<T?, String>>,
    selected: T?,
    onSelect: (T?) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    options.forEach { (value, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(value) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Text(label)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(9)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

/** Speed and data limits for one device, opened from the Clients page. */
@Composable
fun ClientLimitsDialog(mac: String, deviceName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaults = remember { LimitsStore.defaults() }
    val current = remember { LimitsStore.clientOverride(mac) }
    var down by remember { mutableStateOf(current.down?.toString() ?: "") }
    var up by remember { mutableStateOf(current.up?.toString() ?: "") }
    var quota by remember { mutableStateOf(current.quota?.toString() ?: "") }
    var rule by remember { mutableStateOf(current.rule) }
    var action by remember { mutableStateOf(current.action) }
    var useVpn by remember { mutableStateOf(mac.lowercase() !in be.mygod.vpnhotspot.xray.XrayController.directMacs()) }
    var used by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(mac) {
        used = try {
            LimitsEnforcer.usedBytes(mac)
        } catch (e: Exception) {
            null
        }
    }
    fun defaultText(value: Long) = if (value > 0) "default: $value" else "default: no limit"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Limits for $deviceName") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Leave a field empty to use the default from Settings. 0 means no limit.")
                NumberField("Download speed (kB/s)", down, { down = it }, defaultText(defaults.down))
                NumberField("Upload speed (kB/s)", up, { up = it }, defaultText(defaults.up))
                NumberField("Data quota (MB)", quota, { quota = it }, defaultText(defaults.quota))
                ChoiceRow(
                    "Reset the quota",
                    listOf<Pair<ResetRule?, String>>(null to "Default (${defaults.rule.label})") +
                        ResetRule.entries.map { it to it.label },
                    rule,
                ) { rule = it }
                ChoiceRow(
                    "When the quota is used up",
                    listOf<Pair<OverAction?, String>>(null to "Default (${defaults.action.label})") +
                        OverAction.entries.map { it to it.label },
                    action,
                ) { action = it }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Send this device through the VPN (applies next time you connect)", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = useVpn, onCheckedChange = { useVpn = it })
                }
                Text(
                    used?.let { "Used in this period: ${Formatter.formatFileSize(context, it)}" }
                        ?: "Usage counting starts once a quota is set.",
                )
                TextButton(onClick = {
                    scope.launch {
                        LimitsEnforcer.resetUsage(mac)
                        used = 0
                    }
                }) { Text("Reset usage now") }
            }
        },
        confirmButton = {
            DialogConfirmButton(onClick = {
                LimitsStore.setOverride(mac, LimitOverride(
                    down = down.toLongOrNull(),
                    up = up.toLongOrNull(),
                    quota = quota.toLongOrNull(),
                    rule = rule,
                    action = action,
                ))
                be.mygod.vpnhotspot.xray.XrayController.setDirect(mac, !useVpn)
                LimitsEnforcer.ensureRunning()
                LimitsEnforcer.requestUpdate()
                onDismiss()
            }) { Text(stringResourceOk()) }
        },
        dismissButton = {
            DialogDismissButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun stringResourceOk() = androidx.compose.ui.res.stringResource(android.R.string.ok)

/** Default limits for every device, shown on the Settings page. */
@Composable
fun DefaultLimitsRow() {
    var editing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(LimitsStore.defaults()) }
    fun speed(v: Long) = if (v > 0) "$v kB/s" else "no limit"
    fun summary(d: Defaults) = "Download ${speed(d.down)}, upload ${speed(d.up)}, quota " +
        (if (d.quota > 0) "${d.quota} MB, ${d.rule.label.lowercase()}" else "no limit")
    PreferenceRow(
        icon = R.drawable.ic_auto_timer,
        title = "Speed and data limits",
        summary = summary(saved),
        onClick = { editing = true },
    )
    if (editing) {
        var down by remember { mutableStateOf(if (saved.down > 0) saved.down.toString() else "") }
        var up by remember { mutableStateOf(if (saved.up > 0) saved.up.toString() else "") }
        var quota by remember { mutableStateOf(if (saved.quota > 0) saved.quota.toString() else "") }
        var rule by remember { mutableStateOf<ResetRule?>(saved.rule) }
        var action by remember { mutableStateOf<OverAction?>(saved.action) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Default limits for every device") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Empty or 0 means no limit. You can change these for one device from the Clients page.")
                    NumberField("Download speed (kB/s)", down, { down = it }, "no limit")
                    NumberField("Upload speed (kB/s)", up, { up = it }, "no limit")
                    NumberField("Data quota (MB)", quota, { quota = it }, "no limit")
                    ChoiceRow("Reset the quota", ResetRule.entries.map { it to it.label }, rule) {
                        rule = it ?: rule
                    }
                    ChoiceRow("When the quota is used up", OverAction.entries.map { it to it.label }, action) {
                        action = it ?: action
                    }
                }
            },
            confirmButton = {
                DialogConfirmButton(onClick = {
                    val d = Defaults(
                        down = down.toLongOrNull() ?: 0,
                        up = up.toLongOrNull() ?: 0,
                        quota = quota.toLongOrNull() ?: 0,
                        rule = rule ?: ResetRule.MONTHLY,
                        action = action ?: OverAction.BLOCK,
                    )
                    LimitsStore.setDefaults(d)
                    saved = d
                    LimitsEnforcer.ensureRunning()
                    LimitsEnforcer.requestUpdate()
                    editing = false
                }) { Text(stringResourceOk()) }
            },
            dismissButton = {
                DialogDismissButton(onClick = { editing = false }) { Text("Cancel") }
            },
        )
    }
}

