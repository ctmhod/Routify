package be.mygod.vpnhotspot.xray

import android.content.Context
import android.os.Build
import android.os.Process
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.limits.RunShell
import be.mygod.vpnhotspot.root.RootManager
import dalvik.system.BaseDexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Starts and stops the bundled Xray-core binary as root and redirects hotspot TCP and DNS through it.
 * UDP uses mangle TPROXY when the kernel supports it. Design: nat REDIRECT on hotspot interfaces to a dokodemo-door inbound; Xray's own outbound is marked with bit 24
 * and skipped by the redirect chain, so there is no loop. Without TPROXY support, other UDP stays direct.
 */
object XrayController {
    private const val TCP_PORT = 12345
    private const val DNS_PORT = 12346
    private const val UDP_PORT = 12347
    /** Bit 25: marks UDP that TPROXY hands to Xray (routed to loopback by a policy rule). */
    private const val TMARK = 0x2000000
    private const val UCHAIN = "ROUTIFY_UDP"
    /** Bit 24: outside the netId/permission bits Android netd uses in fwmark, so routing is not affected. */
    private const val MARK = 0x1000000
    private const val CHAIN = "ROUTIFY_XRAY"
    private const val PREFS = "routify_xray"

    val running: StateFlow<Boolean> get() = _running
    private val _running = MutableStateFlow(false)
    val message: StateFlow<String> get() = _message
    private val _message = MutableStateFlow("Stopped")

    private val dir get() = app.getDir("xray", Context.MODE_PRIVATE)
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedServers(): List<XrayServer> {
        val arr = JSONArray(prefs.getString("links", "[]"))
        return (0 until arr.length()).mapNotNull { XrayServer.parse(arr.getString(it)) }
    }
    fun savedLinks(): List<String> {
        val arr = JSONArray(prefs.getString("links", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }
    fun saveLinks(links: List<String>) = prefs.edit().putString("links", JSONArray(links).toString()).apply()
    var selected: Int
        get() = prefs.getInt("selected", 0)
        set(v) = prefs.edit().putInt("selected", v).apply()


    /** Devices (MAC addresses, lower case) that skip the VPN and use the normal connection. Applies on the next Connect. */
    /** DNS server that hotspot clients use while the VPN is on (asked through the tunnel). */
    var dnsServer: String
        get() = prefs.getString("dns_server", null)?.takeIf { isIpv4(it) } ?: "1.1.1.1"
        set(v) = prefs.edit().putString("dns_server", v).apply()
    fun isIpv4(s: String) = Regex("^((25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])$").matches(s)
    /** Xray log level: warning, info or debug. */
    var logLevel: String
        get() = prefs.getString("log_level", null)?.takeIf { it in LOG_LEVELS } ?: "warning"
        set(v) = prefs.edit().putString("log_level", v).apply()
    val LOG_LEVELS = listOf("warning", "info", "debug")

    fun directMacs(): Set<String> {
        val arr = JSONArray(prefs.getString("direct_macs", "[]"))
        return (0 until arr.length()).map { arr.getString(it).lowercase() }.toSet()
    }
    fun setDirect(mac: String, direct: Boolean) {
        val m = directMacs().toMutableSet()
        if (direct) m.add(mac.lowercase()) else m.remove(mac.lowercase())
        prefs.edit().putString("direct_macs", JSONArray(m.toList()).toString()).apply()
    }

    private fun config(server: XrayServer): String = JSONObject()
        .put("log", JSONObject().put("loglevel", logLevel))
        .put("inbounds", JSONArray()
            .put(JSONObject().put("tag", "tcp-in").put("port", TCP_PORT).put("listen", "0.0.0.0")
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("network", "tcp").put("followRedirect", true))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tproxy", "redirect"))))
            .put(JSONObject().put("tag", "dns-in").put("port", DNS_PORT).put("listen", "0.0.0.0")
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("address", dnsServer).put("port", 53).put("network", "tcp,udp")))
            .put(JSONObject().put("tag", "udp-in").put("port", UDP_PORT).put("listen", "0.0.0.0")
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("network", "udp").put("followRedirect", true))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tproxy", "tproxy")))))
        .put("outbounds", JSONArray().put(server.outbound()))
        .toString()


    /**
     * The Xray binary ships gzip-compressed inside the APK as lib/<abi>/libxraygz.so (a gzip file, not an ELF),
     * so each CPU type only carries its own copy and the APK stays small. It is unpacked once into app data.
     */
    private fun prepareBinary(): String? {
        val info = app.applicationInfo
        val stamp = File(dir, "xray-bin.stamp")
        val out = File(dir, "xray-bin")
        java.util.zip.ZipFile(info.sourceDir).use { zip ->
            val abi = Build.SUPPORTED_ABIS.firstOrNull { zip.getEntry("lib/$it/libxraygz.so") != null } ?: return null
            val want = abi + ":" + app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            if (!out.exists() || !stamp.exists() || stamp.readText() != want) {
                val tmp = File(dir, "xray-bin.tmp")
                java.util.zip.GZIPInputStream(zip.getInputStream(zip.getEntry("lib/$abi/libxraygz.so"))).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.setReadable(true, false)
                tmp.setExecutable(true, false)
                if (!tmp.renameTo(out)) { tmp.delete(); return null }
                stamp.writeText(want)
            }
        }
        return out.absolutePath
    }

    /** Go's resolver has no resolv.conf on Android, so resolve the server name here and keep the name for SNI and Host. */
    private fun resolve(s: XrayServer): XrayServer {
        if (Regex("^[0-9.]+$").matches(s.host) || ':' in s.host) return s
        val ip = try {
            java.net.InetAddress.getAllByName(s.host).firstOrNull { it is java.net.Inet4Address }?.hostAddress
        } catch (_: Exception) { null } ?: return s
        val name = s.sni.ifEmpty { s.wsHost.ifEmpty { s.host } }
        return s.copy(
            host = ip,
            sni = if (s.security != "none") name else s.sni,
            wsHost = if (s.network == "ws" && s.wsHost.isEmpty()) s.host else s.wsHost,
        )
    }

    private fun linker() = if (Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** [ifaces] are the active hotspot interfaces whose clients get proxied. */
    suspend fun start(server: XrayServer, ifaces: Collection<String>) {
        try {
            startInner(server, ifaces)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail("Connect failed", e)
        }
    }

    /** Never let a failure reach the UI thread: show a short message and keep a redacted log. */
    private fun fail(what: String, e: Throwable) {
        val text = redact(e.stackTraceToString())
        appendLog("$what: $text")
        _running.value = false
        _message.value = "$what: ${e.javaClass.simpleName} ${redact(e.message ?: "")}".trim().take(300)
    }

    private fun redact(s: String) = s
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<id>")
        .replace(Regex("(?i)(vless|vmess)://\\S+"), "<link>")

    fun appendLog(line: String) = be.mygod.vpnhotspot.util.AppLog.add("vpn", line)
    fun readLog(): String = be.mygod.vpnhotspot.util.AppLog.read()
    fun clearLog() = be.mygod.vpnhotspot.util.AppLog.clear()
    fun crashLog(): String = ""

    /** The app-wide crash log is installed in App; kept so older callers still compile. */
    fun installCrashLog() { }

    private suspend fun startInner(server: XrayServer, ifaces: Collection<String>) {
        val lib = withContext(Dispatchers.IO) { prepareBinary() }
        if (lib == null) { _message.value = "Xray engine missing from this build"; return }
        if (ifaces.isEmpty()) { _message.value = "Start the hotspot first"; return }
        val resolved = withContext(Dispatchers.IO) { resolve(server) }
        val cfg = android.util.Base64.encodeToString(config(resolved).toByteArray(), android.util.Base64.NO_WRAP)
        val d = dir.absolutePath
        val rules = ifaces.joinToString("\n") { i ->
            "iptables -w -t nat -I PREROUTING -i ${quote(i)} -j $CHAIN"
        }
        val direct = directMacs().filter { Regex("^[0-9a-f:]{17}$").matches(it) }
            .joinToString("\n") { "iptables -w -t nat -A $CHAIN -m mac --mac-source $it -j RETURN" }
        val udpJumps = ifaces.joinToString("\n") { i ->
            "iptables -w -t mangle -I PREROUTING -i ${quote(i)} -p udp -j $UCHAIN"
        }
        val udpDirect = directMacs().filter { Regex("^[0-9a-f:]{17}$").matches(it) }
            .joinToString("\n") { "iptables -w -t mangle -A $UCHAIN -m mac --mac-source $it -j RETURN" }
        val script = """
            umask 022
            ${stopScript(d)}
            echo ${quote(cfg)} | base64 -d > $d/config.json
            cd $d
            S=${'$'}(command -v setsid || true)
            XRAY_LOCATION_ASSET=$d ${'$'}S nohup ${linker()} ${quote(lib)} run -c $d/config.json > $d/xray.log 2>&1 &
            echo ${'$'}! > $d/xray.pid
            sleep 1
            if ! kill -0 ${'$'}(cat $d/xray.pid) 2>/dev/null; then echo failed > $d/state; exit 1; fi
            iptables -w -t nat -N $CHAIN
            $direct
            iptables -w -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $DNS_PORT
            iptables -w -t nat -A $CHAIN -m mark --mark 0x1000000/0x1000000 -j RETURN
            for n in 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4; do
              iptables -w -t nat -A $CHAIN -d ${'$'}n -j RETURN
            done
            iptables -w -t nat -A $CHAIN -p tcp -j REDIRECT --to-ports $TCP_PORT
            $rules
            # UDP (games, calls, QUIC) through TPROXY. Needs kernel support; if missing, UDP stays direct as before.
            echo no > $d/udp
            if iptables -w -t mangle -N $UCHAIN 2>/dev/null; then
              $udpDirect
              iptables -w -t mangle -A $UCHAIN -m mark --mark 0x1000000/0x1000000 -j RETURN
              iptables -w -t mangle -A $UCHAIN -p udp --dport 53 -j RETURN
              for n in 10.0.0.0/8 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 255.255.255.255/32; do
                iptables -w -t mangle -A $UCHAIN -d ${'$'}n -j RETURN
              done
              if iptables -w -t mangle -A $UCHAIN -p udp -j TPROXY --on-port $UDP_PORT --tproxy-mark $TMARK/$TMARK 2>/dev/null; then
                ip rule add fwmark $TMARK/$TMARK lookup 200 pref 90 2>/dev/null
                ip route add local 0.0.0.0/0 dev lo table 200 2>/dev/null
                $udpJumps
                echo yes > $d/udp
              else
                iptables -w -t mangle -F $UCHAIN; iptables -w -t mangle -X $UCHAIN
              fi
            fi
            echo running > $d/state
            chmod -R a+rX $d
        """.trimIndent()
        _message.value = "Starting..."
        RootManager.use { it.execute(RunShell(script)) }
        refresh()
    }

    suspend fun stop() {
        try {
            RootManager.use { it.execute(RunShell(stopScript(dir.absolutePath) + "\necho stopped > ${dir.absolutePath}/state; chmod a+r ${dir.absolutePath}/state")) }
            refresh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail("Disconnect failed", e)
        }
    }

    private fun stopScript(d: String) = """
        iptables -w -t mangle -S PREROUTING 2>/dev/null | grep -E "$UCHAIN" | sed 's/^-A/iptables -w -t mangle -D/' | while read -r l; do sh -c "${'$'}l"; done
        iptables -w -t mangle -F $UCHAIN 2>/dev/null
        iptables -w -t mangle -X $UCHAIN 2>/dev/null
        while ip rule del fwmark $TMARK/$TMARK lookup 200 2>/dev/null; do :; done
        ip route flush table 200 2>/dev/null
        while iptables -w -t nat -D PREROUTING -p tcp -j $CHAIN 2>/dev/null; do :; done
        iptables -w -t nat -S PREROUTING | grep -E "$CHAIN|--to-ports $DNS_PORT" | sed 's/^-A/iptables -w -t nat -D/' | while read -r l; do sh -c "${'$'}l"; done
        iptables -w -t nat -F $CHAIN 2>/dev/null
        iptables -w -t nat -X $CHAIN 2>/dev/null
        [ -f $d/xray.pid ] && kill ${'$'}(cat $d/xray.pid) 2>/dev/null
        rm -f $d/xray.pid
    """.trimIndent()

    fun refresh() = try { refreshInner() } catch (e: Throwable) { _message.value = "Status unavailable" }

    private fun refreshInner() {
        var state = File(dir, "state").takeIf { it.exists() }?.readText()?.trim()
        if (state == "running") {
            // After a reboot the state file stays behind (Xray and the iptables rules are gone). /proc is hidden from apps.
            val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            if (File(dir, "state").lastModified() < bootTime) state = "stopped"
        }
        _running.value = state == "running"
        _message.value = when (state) {
            "running" -> if (File(dir, "udp").takeIf { it.exists() }?.readText()?.trim() == "yes") "Connected (TCP, DNS and UDP)"
                else "Connected (TCP and DNS; this phone's kernel has no UDP proxy support)"
            "failed" -> "Failed to start. " + redact(File(dir, "xray.log").takeIf { it.exists() }?.readText()?.takeLast(300) ?: "")
            else -> "Stopped"
        }
    }
}

