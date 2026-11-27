package be.mygod.vpnhotspot.util

import android.util.Log
import be.mygod.vpnhotspot.App.Companion.app
import timber.log.Timber
import java.io.File
import java.util.Date

/**
 * Routify: one app-wide error log for development. Collects warnings and errors from everywhere that logs through
 * Timber (hotspot, root and iptables, limits, boot) and uncaught crashes. Personal data is hidden before writing.
 */
object AppLog {
    private const val MAX = 60_000
    private val file get() = File(app.filesDir, "routify-error.log")

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val link = Regex("(?i)(vless|vmess|trojan|ss)://\\S+")
    private val mac = Regex("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b")
    private val ipv4 = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
    private val ipv6 = Regex("(?i)\\b([0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}\\b")
    private val secret = Regex("(?i)(ssid|passphrase|password|psk|pass|key|token)(\"?\\s*[=:]\\s*\"?)[^\",\\s}]+")

    fun redact(s: String): String = s
        .replace(link, "<link>").replace(uuid, "<id>").replace(mac, "<mac>")
        .replace(ipv6, "<ip>").replace(ipv4, "<ip>").replace(secret) { it.groupValues[1] + it.groupValues[2] + "<hidden>" }

    @Synchronized
    fun add(tag: String, text: String) {
        try {
            val f = file
            val old = if (f.exists()) f.readText().takeLast(MAX) else ""
            f.writeText(old + Date() + " [" + tag + "] " + redact(text).take(4000) + "\n")
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun read(): String = try { file.takeIf { it.exists() }?.readText().orEmpty() } catch (_: Throwable) { "" }

    @Synchronized
    fun clear() { try { file.delete() } catch (_: Throwable) { } }

    /** Records warnings and errors from Timber. */
    class Tree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.WARN) return
            if (message.startsWith("StrictMode")) return
            // The root helper says "connected to ..." through stderr every time it starts. That is normal, not an error.
            if (message.startsWith("connected to ")) return
            // No root is normal for proxy mode: do not fill the log with root-search failures.
            if (t != null && t.stackTraceToString().let { "NoShellException" in it || "Cannot run program" in it }) return
            if ("Root shell is not available" in message || "NoShellException" in message) return
            val level = if (priority >= Log.ERROR) "E" else "W"
            add((tag ?: "app") + "/" + level, message + (t?.let { "\n" + it.stackTraceToString() } ?: ""))
        }
    }

    private var installed = false

    /** Saves uncaught crashes so they show up next time the app opens. */
    fun install() {
        if (installed) return
        installed = true
        Timber.plant(Tree())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            add("CRASH in " + thread.name, e.stackTraceToString())
            previous?.uncaughtException(thread, e)
        }
    }
}

