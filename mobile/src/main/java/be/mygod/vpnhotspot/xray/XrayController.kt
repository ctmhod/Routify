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
 * Design: nat REDIRECT on hotspot interfaces to a dokodemo-door inbound; Xray's own outbound is marked 255
 * and skipped by the redirect chain, so there is no loop. UDP other than DNS is not proxied yet.
 */
object XrayController {
    private const val TCP_PORT = 12345
    private const val DNS_PORT = 12346
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
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("inbounds", JSONArray()
            .put(JSONObject().put("tag", "tcp-in").put("port", TCP_PORT).put("listen", "0.0.0.0")
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("network", "tcp").put("followRedirect", true))
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tproxy", "redirect"))))
            .put(JSONObject().put("tag", "dns-in").put("port", DNS_PORT).put("listen", "0.0.0.0")
                .put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("address", "1.1.1.1").put("port", 53).put("network", "tcp,udp"))))
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
            echo running > $d/state
            chmod -R a+rX $d
        """.trimIndent()
        _message.value = "Starting..."
        RootManager.use { it.execute(RunShell(script)) }
        refresh()
    }

    suspend fun stop() {
        RootManager.use { it.execute(RunShell(stopScript(dir.absolutePath) + "\necho stopped > ${dir.absolutePath}/state; chmod a+r ${dir.absolutePath}/state")) }
        refresh()
    }

    private fun stopScript(d: String) = """
        while iptables -w -t nat -D PREROUTING -p tcp -j $CHAIN 2>/dev/null; do :; done
        iptables -w -t nat -S PREROUTING | grep -E "$CHAIN|--to-ports $DNS_PORT" | sed 's/^-A/iptables -w -t nat -D/' | while read -r l; do sh -c "${'$'}l"; done
        iptables -w -t nat -F $CHAIN 2>/dev/null
        iptables -w -t nat -X $CHAIN 2>/dev/null
        [ -f $d/xray.pid ] && kill ${'$'}(cat $d/xray.pid) 2>/dev/null
        rm -f $d/xray.pid
    """.trimIndent()

    fun refresh() {
        val state = File(dir, "state").takeIf { it.exists() }?.readText()?.trim()
        _running.value = state == "running"
        _message.value = when (state) {
            "running" -> "Connected"
            "failed" -> "Failed to start. " + (File(dir, "xray.log").takeIf { it.exists() }?.readText()?.takeLast(200) ?: "")
            else -> "Stopped"
        }
    }
}

