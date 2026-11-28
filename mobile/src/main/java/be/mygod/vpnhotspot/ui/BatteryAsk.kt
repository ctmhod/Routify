package be.mygod.vpnhotspot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Asks once for "unrestricted battery" before something is started from the Tethering page. Declining never blocks the start. */
object BatteryAsk {
    var pending by mutableStateOf<(() -> Unit)?>(null)
    var resume: (() -> Unit)? = null
    private fun prefs(c: Context) = c.getSharedPreferences("routify_battery", Context.MODE_PRIVATE)
    fun needed(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(c.packageName) && !prefs(c).getBoolean("asked", false)
    }
    fun markAsked(c: Context) = prefs(c).edit().putBoolean("asked", true).apply()
    /** Runs [action] now, or after the one-time question has been answered. */
    fun gate(c: Context, action: () -> Unit) { if (needed(c)) pending = action else action() }
}

@Composable
fun BatteryAskDialog() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val r = BatteryAsk.resume; BatteryAsk.resume = null; r?.invoke()
    }
    val action = BatteryAsk.pending ?: return
    val decline = { BatteryAsk.markAsked(context); BatteryAsk.pending = null; action() }
    AlertDialog(
        onDismissRequest = decline,
        title = { Text("Allow unrestricted battery use?") },
        text = { Text("Android can stop Routify in the background to save battery, which can switch your hotspot or VPN off after a while. Allowing unrestricted battery use keeps it running. Android will show its own confirmation next. You can say Not now and everything still works.") },
        confirmButton = {
            TextButton(onClick = {
                BatteryAsk.markAsked(context); BatteryAsk.pending = null; BatteryAsk.resume = action
                try {
                    launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
                } catch (e: Exception) {
                    BatteryAsk.resume = null; action()
                }
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = decline) { Text("Not now") } },
    )
}
