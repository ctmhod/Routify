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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import be.mygod.vpnhotspot.util.AppLog
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

    class Release(val tag: String, val apkName: String, val apkUrl: String, val size: Long, val throttleMs: Long = 0)

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
        Release(json.getString("tag_name"), asset.getString("name"), url, asset.optLong("size", -1),
            if (test.isFile) json.optLong("throttle_ms", 0) else 0)
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

    private val downloadLock = Mutex()
    private fun updateDir(context: Context) = File(context.cacheDir, "update").apply { mkdirs() }
    fun updateFile(context: Context) = File(updateDir(context), "Routify-update.apk")

    /** Removes half-finished downloads, and a finished file that is no longer newer than the installed app. */
    fun cleanup(context: Context, keepFinal: Boolean = true) {
        val dir = File(context.cacheDir, "update")
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part")) f.delete()
            else if (f.name != "Routify-update.apk") f.delete()
            else if (!keepFinal) f.delete()
            else try { validate(context, f) } catch (_: Exception) { f.delete() }
        }
    }

    /**
     * Downloads the APK into the app's cache and checks that it is really Routify. Only one download runs at a time, the file is
     * written as ".part" and renamed when complete, and a finished valid file is reused instead of downloading again.
     */
    suspend fun download(context: Context, r: Release, progress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadLock.withLock {
            val dir = updateDir(context)
            val out = updateFile(context)
            val part = File(dir, "Routify-update.apk.part")
            // Android restarts the app after you allow installs, so reuse a file that was already downloaded and is still valid.
            if (out.isFile && (r.size <= 0 || out.length() == r.size)) {
                try {
                    validate(context, out)
                    progress(out.length(), out.length())
                    AppLog.add("update", "Reusing the file that was already downloaded")
                    return@withLock out
                } catch (e: Exception) {
                    Timber.i("Cached update not usable: ${e.message}")
                }
            }
            dir.listFiles()?.forEach { it.delete() }
            AppLog.add("update", "Download started (${r.tag})")
            try {
                if (r.apkUrl.startsWith("file:")) {
                    val src = File(Uri.parse(r.apkUrl).path ?: "")
                    val total = src.length()
                    var done = 0L
                    src.inputStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                progress(done, total)
                                if (r.throttleMs > 0) Thread.sleep(r.throttleMs)
                            }
                        }
                    }
                } else {
                    val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15000
                        c.readTimeout = 30000
                        if (c.responseCode != 200) throw IllegalStateException("Download failed (${c.responseCode}).")
                        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
                        var done = 0L
                        c.inputStream.use { input ->
                            part.outputStream().use { o ->
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
                validate(context, part)
                if (!part.renameTo(out)) throw IllegalStateException("Could not save the downloaded file.")
            } catch (e: Throwable) {
                part.delete()
                out.delete()
                if (e is CancellationException) AppLog.add("update", "Download cancelled, partial file removed")
                throw e
            }
            out
            }
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

sealed interface UpdateState {
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: AppUpdater.Release) : UpdateState
    data class Downloading(val done: Long, val total: Long) : UpdateState
    data class Ready(val file: File, val tag: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * One update flow for the whole app process, so closing and reopening the dialog (or the screen turning) never starts a second
 * download or shows a second screen. If Android restarts the app after "Install unknown apps" is allowed, [resumeAfterPermission]
 * opens the installer once, straight away.
 */
object UpdateController {
    val state = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val open = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastRelease: AppUpdater.Release? = null
    private val busy get() = job?.isActive == true

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("routify_update", Context.MODE_PRIVATE)

    private fun readable(e: Exception) = when (e) {
        is java.io.IOException -> "Could not reach GitHub. Check your internet connection and try again."
        else -> e.message ?: "Something went wrong."
    }

    /** Called when the user opens the dialog. Never interrupts a download or a finished update that is waiting. */
    fun show(context: Context) {
        open.value = true
        val s = state.value
        if (s is UpdateState.Downloading && busy) return
        if (s is UpdateState.Ready && s.file.isFile) return
        check(context)
    }

    fun check(context: Context) {
        if (busy && state.value is UpdateState.Downloading) return
        val app = context.applicationContext
        state.value = UpdateState.Checking
        job?.cancel()
        job = scope.launch {
            try {
                val r = AppUpdater.fetchLatest(app)
                lastRelease = r
                state.value = if (AppUpdater.isNewer(r.tag, AppUpdater.currentVersion(app))) UpdateState.Available(r)
                else UpdateState.UpToDate(AppUpdater.currentVersion(app))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Update check failed")
                state.value = UpdateState.Failed(readable(e))
            }
        }
    }

    fun download(context: Context, r: AppUpdater.Release) {
        if (busy && state.value is UpdateState.Downloading) return   // a second tap reuses the running download
        val app = context.applicationContext
        lastRelease = r
        state.value = UpdateState.Downloading(0, r.size)
        job?.cancel()
        job = scope.launch {
            try {
                val f = AppUpdater.download(app, r) { d, t -> state.value = UpdateState.Downloading(d, t) }
                tryInstall(app, f, r.tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Update download failed")
                AppLog.add("update", "Download failed: ${e.message}")
                state.value = UpdateState.Failed(readable(e))
            }
        }
    }

    /** Stops the running download, deletes the partial file and goes back to one clear "Download" button. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        job = null
        AppUpdater.cleanup(app, keepFinal = false)
        val r = lastRelease
        state.value = if (r != null) UpdateState.Available(r) else UpdateState.Checking
        if (r == null) check(app)
    }

    fun tryInstall(context: Context, file: File, tag: String) {
        state.value = UpdateState.Ready(file, tag)
        if (AppUpdater.canInstall(context)) {
            try { AppUpdater.install(context, file) } catch (e: Exception) {
                Timber.w(e, "Could not start the installer")
                state.value = UpdateState.Failed("Could not open Android's installer.")
            }
        }
    }

    /** The user is sent to Android's settings to allow installs. Remember it, so coming back installs directly. */
    fun openPermissionSettings(context: Context) {
        prefs(context).edit().putBoolean("awaiting_permission", true).apply()
        AppUpdater.openInstallSettings(context)
    }

    /** Called from the main screen each time it comes to the front. Opens the installer once if the user just allowed installs. */
    fun resumeAfterPermission(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean("awaiting_permission", false)) return
        p.edit().putBoolean("awaiting_permission", false).apply()
        val file = AppUpdater.updateFile(context)
        if (!AppUpdater.canInstall(context) || !file.isFile) { AppLog.add("update", "Back from settings without permission or file"); return }
        try {
            AppUpdater.validate(context, file)
            AppLog.add("update", "Back from settings: opening the installer once")
            state.value = UpdateState.Ready(file, "")
            AppUpdater.install(context, file)
        } catch (e: Exception) {
            AppLog.add("update", "Not installing after settings: ${e.message}")
        }
    }
}

@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val s by UpdateController.state.collectAsState()
    val open by UpdateController.open.collectAsState()
    val current = remember { AppUpdater.currentVersion(context) }

    PreferenceRow(
        icon = R.drawable.ic_system_update,
        title = "Check for updates",
        summary = "Installed version: $current. Looks for a newer Routify on GitHub.",
        onClick = { UpdateController.show(context) },
    )
    if (open) {
        AlertDialog(
            // Closing the dialog never cancels a download. Use the Cancel button for that.
            onDismissRequest = { UpdateController.open.value = false },
            title = { Text("Check for updates") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (val st = s) {
                        UpdateState.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.padding(2.dp))
                            Text("Checking for updates...")
                        }
                        is UpdateState.UpToDate -> Text("You are on the latest version (${st.version}).")
                        is UpdateState.Available -> Text(
                            "Version ${st.release.tag.removePrefix("v")} is available (${st.release.size / 1_000_000} MB). " +
                                "You have $current.")
                        is UpdateState.Downloading -> {
                            val f = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
                            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                            Text(if (st.total > 0) "Downloading... ${(f * 100).toInt()}%" else "Downloading...")
                        }
                        is UpdateState.Ready -> if (AppUpdater.canInstall(context)) {
                            Text("Downloaded. Android will ask you to confirm the update.")
                        } else {
                            Text("Android needs your OK before Routify can install updates. Open the settings, " +
                                "allow installs from Routify and come back. The installer opens by itself.")
                        }
                        is UpdateState.Failed -> Text(st.message)
                    }
                }
            },
            confirmButton = {
                when (val st = s) {
                    is UpdateState.Available -> TextButton(onClick = { UpdateController.download(context, st.release) }) { Text("Download and install") }
                    is UpdateState.Ready -> Column {
                        if (!AppUpdater.canInstall(context)) {
                            TextButton(onClick = { UpdateController.openPermissionSettings(context) }) { Text("Open settings") }
                        } else {
                            TextButton(onClick = { UpdateController.tryInstall(context, st.file, st.tag) }) { Text("Install") }
                        }
                    }
                    is UpdateState.Failed -> TextButton(onClick = { UpdateController.check(context) }) { Text("Try again") }
                    else -> {}
                }
            },
            dismissButton = {
                if (s is UpdateState.Downloading) {
                    TextButton(onClick = { UpdateController.cancel(context) }) { Text("Cancel download") }
                    TextButton(onClick = { UpdateController.open.value = false }) { Text("Hide") }
                } else TextButton(onClick = { UpdateController.open.value = false }) { Text("Close") }
            },
        )
    }
}
