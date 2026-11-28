package be.mygod.vpnhotspot.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.security.MessageDigest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import be.mygod.vpnhotspot.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Routify: finds the newest release on GitHub, downloads the right APK for this device and hands it to Android to install. */
object AppUpdater {
    private const val FEED = "https://api.github.com/repos/ctmhod/Routify/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/ctmhod/Routify/releases/download/"
    /** Only for emulator tests: a file in the app's private folder (nobody else can write it) that replaces the GitHub answer. */
    private const val TEST_FEED = "update-test-feed.json"

    class Release(val tag: String, val apkName: String, val apkUrl: String, val size: Long)

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { p ->
        p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Picks the APK that fits this device's CPU, else the universal one. */
    fun pickAsset(assets: List<JSONObject>): JSONObject? {
        val apks = assets.filter { it.optString("name").endsWith(".apk") }
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.optString("name").endsWith("-$abi.apk") }?.let { return it }
        }
        return apks.firstOrNull { it.optString("name").contains("universal") }
    }

    /** Returns the latest release, or throws with a message that is fine to show to the user. */
    suspend fun fetchLatest(context: Context): Release = withContext(Dispatchers.IO) {
        val test = File(context.filesDir, TEST_FEED)
        val text = if (test.isFile) test.readText() else {
            val c = URL(FEED).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode != 200) throw IllegalStateException("GitHub answered ${c.responseCode}.")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        val json = JSONObject(text)
        val arr = json.getJSONArray("assets")
        val asset = pickAsset((0 until arr.length()).map { arr.getJSONObject(it) })
            ?: throw IllegalStateException("The newest release has no APK for this device.")
        val url = asset.getString("browser_download_url")
        if (!test.isFile && !url.startsWith(DOWNLOAD_PREFIX)) throw IllegalStateException("Unexpected download address.")
        Release(json.getString("tag_name"), asset.getString("name"), url, asset.optLong("size", -1))
    }

    private fun certs(info: android.content.pm.PackageInfo): Set<String> {
        val signers = info.signingInfo?.apkContentsSigners ?: return emptySet()
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Throws a plain message unless the file is Routify, signed with the same key as the installed app, and strictly newer. */
    fun validate(context: Context, file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val fresh = pm.getPackageArchiveInfo(file.path, flags)
        if (fresh == null || fresh.packageName != context.packageName) {
            throw IllegalStateException("The downloaded file is not Routify. Not installing it.")
        }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val a = certs(fresh)
        if (a.isEmpty() || a != certs(installed)) {
            throw IllegalStateException("The downloaded file is signed with a different key than your Routify. Not installing it.")
        }
        if (fresh.longVersionCode <= installed.longVersionCode) {
            throw IllegalStateException("The downloaded file is not newer than the version you have. Not installing it.")
        }
    }

    /** Downloads the APK into the app's cache and checks that it is really Routify. */
    suspend fun download(context: Context, r: Release, progress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val out = File(dir, "Routify-update.apk")
            // Android restarts the app after you allow installs, so reuse a file that was already downloaded and is still valid.
            if (r.size > 0 && out.length() == r.size) {
                try {
                    validate(context, out)
                    progress(r.size, r.size)
                    return@withContext out
                } catch (e: Exception) {
                    Timber.i("Cached update not usable: ${e.message}")
                }
            }
            dir.listFiles()?.forEach { it.delete() }
            try {
                if (r.apkUrl.startsWith("file:")) {
                    val src = File(Uri.parse(r.apkUrl).path ?: "")
                    src.copyTo(out, overwrite = true)
                    progress(out.length(), out.length())
                } else {
                    val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15000
                        c.readTimeout = 30000
                        if (c.responseCode != 200) throw IllegalStateException("Download failed (${c.responseCode}).")
                        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
                        var done = 0L
                        c.inputStream.use { input ->
                            out.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                var last = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    done += n
                                    val now = System.currentTimeMillis()
                                    if (now - last > 150) { last = now; progress(done, total) }
                                }
                            }
                        }
                        progress(done, total)
                        if (total > 0 && done != total) throw IllegalStateException("Download was cut short. Try again.")
                    } finally {
                        c.disconnect()
                    }
                }
                validate(context, out)
            } catch (e: Exception) {
                out.delete()
                throw e
            }
            out
        }

    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.log", file)
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private sealed interface UpdateState {
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: AppUpdater.Release) : UpdateState
    data class Downloading(val done: Long, val total: Long) : UpdateState
    data class Ready(val file: File, val tag: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var job by remember { mutableStateOf<Job?>(null) }
    val current = remember { AppUpdater.currentVersion(context) }

    fun readable(e: Exception) = when (e) {
        is java.io.IOException -> "Could not reach GitHub. Check your internet connection and try again."
        else -> e.message ?: "Something went wrong."
    }

    fun check() {
        state = UpdateState.Checking
        job?.cancel()
        job = scope.launch {
            try {
                val r = AppUpdater.fetchLatest(context)
                state = if (AppUpdater.isNewer(r.tag, current)) UpdateState.Available(r) else UpdateState.UpToDate(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Update check failed")
                state = UpdateState.Failed(readable(e))
            }
        }
    }

    fun tryInstall(file: File, tag: String) {
        state = UpdateState.Ready(file, tag)
        if (AppUpdater.canInstall(context)) {
            try { AppUpdater.install(context, file) } catch (e: Exception) {
                Timber.w(e, "Could not start the installer")
                state = UpdateState.Failed("Could not open Android's installer.")
            }
        }
    }

    fun download(r: AppUpdater.Release) {
        state = UpdateState.Downloading(0, r.size)
        job?.cancel()
        job = scope.launch {
            try {
                val f = AppUpdater.download(context, r) { d, t -> state = UpdateState.Downloading(d, t) }
                tryInstall(f, r.tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Update download failed")
                state = UpdateState.Failed(readable(e))
            }
        }
    }

    PreferenceRow(
        icon = R.drawable.ic_system_update,
        title = "Check for updates",
        summary = "Installed version: $current. Looks for a newer Routify on GitHub.",
        onClick = { open = true; check() },
    )
    if (open) {
        val s = state
        AlertDialog(
            onDismissRequest = { job?.cancel(); open = false },
            title = { Text("Check for updates") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (s) {
                        UpdateState.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.padding(2.dp))
                            Text("Checking for updates...")
                        }
                        is UpdateState.UpToDate -> Text("You are on the latest version (${s.version}).")
                        is UpdateState.Available -> Text(
                            "Version ${s.release.tag.removePrefix("v")} is available (${s.release.size / 1_000_000} MB). " +
                                "You have $current.")
                        is UpdateState.Downloading -> {
                            val f = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                            Text(if (s.total > 0) "Downloading... ${(f * 100).toInt()}%" else "Downloading...")
                        }
                        is UpdateState.Ready -> if (AppUpdater.canInstall(context)) {
                            Text("Downloaded. Android will now ask you to confirm the update.")
                        } else {
                            Text("Android needs your OK before Routify can install updates. Open the settings, " +
                                "allow installs from Routify, come back and press Install.")
                        }
                        is UpdateState.Failed -> Text(s.message)
                    }
                }
            },
            confirmButton = {
                when (s) {
                    is UpdateState.Available -> TextButton(onClick = { download(s.release) }) { Text("Download and install") }
                    is UpdateState.Ready -> Column {
                        if (!AppUpdater.canInstall(context)) {
                            TextButton(onClick = { AppUpdater.openInstallSettings(context) }) { Text("Open settings") }
                        }
                        TextButton(onClick = { tryInstall(s.file, s.tag) }) { Text("Install") }
                    }
                    is UpdateState.Failed -> TextButton(onClick = { check() }) { Text("Try again") }
                    else -> {}
                }
            },
            dismissButton = {
                TextButton(onClick = { job?.cancel(); open = false }) {
                    Text(if (s is UpdateState.Downloading) "Cancel" else "Close")
                }
            },
        )
    }
}
