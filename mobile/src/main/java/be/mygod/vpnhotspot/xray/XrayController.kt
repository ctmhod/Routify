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
    const val HTTP_PORT = 8080
    const val SOCKS_PORT = 1080
    /** Local SOCKS port of the separate engine this phone uses when root mode is on. */
    const val PHONE_PORT = 1081
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
    /** Off: Connect shares to other devices only. On: this phone or tablet is on the VPN too. */
    var phoneVpn: Boolean
        get() = prefs.getBoolean("phone_vpn", false)
        set(v) = prefs.edit().putBoolean("phone_vpn", v).apply()

    private var phoneProc: java.lang.Process? = null

    /**
     * Engine for this device's own traffic. Proxy mode already has one on SOCKS 1080; in root mode the sharing engine runs
     * as root, so this device gets its own engine that runs as Routify (which the VPN skips, so there is no loop).
     * Returns the local SOCKS port, or null when the engine did not start.
     */
    suspend fun startPhoneEngine(server: XrayServer): Int? {
        try {
            if (proxyProc?.isAlive == true) return SOCKS_PORT
            val lib = withContext(Dispatchers.IO) { prepareBinary() } ?: return null
            stopPhoneEngine()
            val resolved = withContext(Dispatchers.IO) { pinIfNeeded(resolve(server)) }
            return withContext(Dispatchers.IO) {
                File(dir, "phone.json").writeText(config(resolved, proxy = true, phone = true))
                val pb = ProcessBuilder("/system/bin/sh", "-c",
                    "echo \$\$ > ${quote(File(dir, "phone.pid").absolutePath)}; exec ${quote(lib)} run -c ${quote(File(dir, "phone.json").absolutePath)}")
                    .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "phone.log"))
                pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
                phoneProc = pb.start()
                Thread.sleep(1200)
                if (phoneProc?.isAlive == true) PHONE_PORT else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            appendLog("Phone engine: ${e.javaClass.simpleName}")
            return null
        }
    }

    fun stopPhoneEngine() {
        try { phoneProc?.destroy() } catch (_: Throwable) { }
        phoneProc = null
        try {
            File(dir, "phone.pid").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()?.let {
                if (try { File("/proc/$it/cmdline").readText().contains("libxray") } catch (_: Throwable) { false }) android.os.Process.killProcess(it)
            }
            File(dir, "phone.pid").delete()
        } catch (_: Throwable) { }
    }

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

    private fun config(server: XrayServer, proxy: Boolean = false, phone: Boolean = false): String {
        val inbounds = JSONArray()
        if (phone) {
            inbounds.put(JSONObject().put("tag", "socks-in").put("port", PHONE_PORT).put("listen", "127.0.0.1")
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true).put("auth", "noauth")))
        } else if (proxy) {
            inbounds.put(JSONObject().put("tag", "http-in").put("port", HTTP_PORT).put("listen", "0.0.0.0")
                .put("protocol", "http").put("settings", JSONObject()))
            inbounds.put(JSONObject().put("tag", "socks-in").put("port", SOCKS_PORT).put("listen", "0.0.0.0")
                .put("protocol", "socks").put("settings", JSONObject().put("udp", true).put("auth", "noauth")))
        } else {
            inbounds
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
                .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tproxy", "tproxy"))))
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", logLevel))
            .put("inbounds", inbounds)
            .put("outbounds", JSONArray().put(server.outbound(mark = !proxy && !phone)))
            .toString()
    }


    /** Xray ships as a real native library (libxray.so) that Android extracts into the app's native-lib folder. */
    private fun prepareBinary(): String? {
        val f = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
        return f.takeIf { it.exists() }?.absolutePath
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


    /**
     * Xray 26 removed "allowInsecure". When the user allows untrusted certificates, read the certificate the server shows
     * right now and pin it by its SHA-256 hash. That accepts an expired or self-made certificate, but only that exact one.
     */
    private fun pinIfNeeded(s: XrayServer): XrayServer {
        if (s.security != "tls" || s.pin.isNotEmpty() || !(s.insecure || allowInsecure)) return s
        return try {
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
            val name = s.sni.ifEmpty { s.wsHost.ifEmpty { s.host } }
            val raw = java.net.Socket()
            raw.connect(java.net.InetSocketAddress(s.host, s.port), 8000)
            raw.soTimeout = 8000
            val ssl = ctx.socketFactory.createSocket(raw, name, s.port, true) as javax.net.ssl.SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                if (!Regex("^[0-9.]+$").matches(name) && ':' !in name) serverNames = listOf(javax.net.ssl.SNIHostName(name))
            }
            ssl.startHandshake()
            val leaf = ssl.session.peerCertificates[0].encoded
            ssl.close()
            val hex = java.security.MessageDigest.getInstance("SHA-256").digest(leaf).joinToString("") { "%02x".format(it) }
            appendLog("Untrusted certificates allowed: pinned the server certificate (${hex.take(12)}...)")
            s.copy(pin = hex)
        } catch (e: Throwable) {
            appendLog("Could not read the server certificate to pin it: ${e.javaClass.simpleName}")
            s
        }
    }

    private fun linker() = if (Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** "auto" picks transparent mode when root works and proxy mode otherwise. */
    var mode: String
        get() = prefs.getString("mode", null)?.takeIf { it in listOf("auto", "transparent", "proxy") } ?: "auto"
        set(v) = prefs.edit().putString("mode", v).apply()

    val rootAvailable: StateFlow<Boolean?> get() = _root
    private val _root = MutableStateFlow<Boolean?>(null)

    suspend fun detectRoot() {
        if (_root.value != null) return
        _root.value = try {
            RootManager.use { it.execute(RunShell("id")) }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    /** True when the local proxy mode is used (manual choice, or auto without root). */
    fun useProxy(): Boolean = mode == "proxy" || (mode == "auto" && _root.value != true)

    private var proxyProc: java.lang.Process? = null

    /** IPv4 addresses of hotspot-like interfaces, where connected devices can reach the proxy. */
    fun hotspotAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && Regex("^(ap|wlan|swlan|softap|rndis|bt-pan|eth)\\d*.*").matches(it.name) }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    } catch (_: Throwable) { emptyList() }

    /**
     * Interfaces that share the internet with other devices, found from the system. The tethering list can miss
     * the Wi-Fi repeater (Wi-Fi Direct group) on some tablets, so root mode also looks for them here.
     */
    fun detectSharingIfaces(): List<String> = try {
        val upstream = try {
            File("/proc/net/route").readLines().drop(1).map { it.split('\t') }
                .filter { it.size > 8 && it[1] == "00000000" && it[7] == "00000000" }.map { it[0] }.toSet()
        } catch (_: Throwable) { emptySet() }
        val name = Regex("^(ap[0-9]*|swlan[0-9]*|softap[0-9]*|p2p[-0-9a-z]*|wlan[1-9][0-9]*|rndis[0-9]*|usb[0-9]*|bt-pan|eth[0-9]*)$")
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { i ->
            i.isUp && !i.isLoopback && name.matches(i.name) && i.name !in upstream &&
                i.interfaceAddresses.any { a ->
                    val ad = a.address
                    ad is java.net.Inet4Address && (ad.isSiteLocalAddress)
                }
        }.map { it.name }
    } catch (_: Throwable) { emptyList() }

    /** Proxy mode: no root needed. Connected devices set an HTTP proxy in their Wi-Fi settings. */
    suspend fun startProxy(server: XrayServer) {
        try {
            val lib = withContext(Dispatchers.IO) { prepareBinary() }
            if (lib == null) { _message.value = "Xray engine missing from this build"; return }
            stopProxyProcess()
            val resolved = withContext(Dispatchers.IO) { pinIfNeeded(resolve(server)) }
            withContext(Dispatchers.IO) {
                File(dir, "config.json").writeText(config(resolved, proxy = true))
                val pb = ProcessBuilder("/system/bin/sh", "-c",
                    "echo \$\$ > ${quote(File(dir, "proxy.pid").absolutePath)}; exec ${quote(lib)} run -c ${quote(File(dir, "config.json").absolutePath)}")
                    .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "xray.log"))
                pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
                proxyProc = pb.start()
                Thread.sleep(1200)
            }
            val p = proxyProc
            if (p == null || !p.isAlive) {
                val log = File(dir, "xray.log").takeIf { it.exists() }?.readText()?.takeLast(300) ?: ""
                _running.value = false
                _message.value = "Failed to start. " + redact(log)
                appendLog("Proxy start failed: $log")
            } else {
                File(dir, "state").writeText("proxy")
                _running.value = true
                _message.value = "Proxy on (HTTP $HTTP_PORT, SOCKS $SOCKS_PORT)"
                try { XrayService.start() } catch (e: Throwable) { appendLog("Background notification: ${e.javaClass.simpleName}") }
                startWatcher()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail("Connect failed", e)
        }
    }


    /** Turns the last Xray errors into one short, plain sentence, or null when the log looks fine. */
    fun diagnose(log: String): String? {
        val errs = log.lines().filter { "[Error]" in it }.takeLast(8).joinToString("\n").lowercase()
        if (errs.isEmpty()) return null
        return when {
            "certificate has expired" in errs -> "the server's security certificate has expired. Renew it, use a new link, or (own server only) turn on Settings > Allow untrusted server certificate."
            "certificate is valid for" in errs -> {
                val m = Regex("certificate is valid for (.+?),? not ([^\\s\\]]+)").find(errs)
                if (m != null) "the server's certificate is for \"${m.groupValues[1]}\" but Routify asked for \"${m.groupValues[2]}\". If that name is wrong, check sni= and host= in the link."
                else "the server's security certificate is not valid for this name."
            }
            "unknown authority" in errs -> "the server's certificate is not trusted (self-made or incomplete chain). Use a new link, or (own server only) turn on Settings > Allow untrusted server certificate."
            "x509" in errs -> "the server's security certificate is not valid for this name."
            "no such host" in errs || "lookup" in errs && "fail" in errs -> "the server name could not be found. Check your internet connection and the link."
            "connection refused" in errs -> "the server refused the connection (wrong port, or the server is down)."
            "timeout" in errs || "timed out" in errs -> "the server did not answer in time."
            "bad handshake" in errs -> "the server rejected the web request. Check the path and host in the link."
            "invalid user" in errs || "invalid request user" in errs || "unauthorized" in errs -> "the server does not accept this ID. Check the link."
            else -> "the server connection is failing. See the Error log."
        }
    }

    /** Off by default. When on, Xray accepts expired or untrusted server certificates (less safe). */
    var allowInsecure: Boolean
        get() = prefs.getBoolean("allow_insecure", false)
        set(v) = prefs.edit().putBoolean("allow_insecure", v).apply()

    @Volatile private var watching = false
    @Volatile private var lastProblem: String? = null

    /** While connected, watches Xray's log so a broken server shows a clear message instead of a silent "on". */
    fun startWatcher() {
        if (watching) return
        watching = true
        lastProblem = null
        Thread {
            try {
                while (_running.value) {
                    Thread.sleep(3000)
                    val log = try { File(dir, "xray.log").takeIf { it.exists() }?.readText()?.takeLast(6000) ?: "" } catch (_: Throwable) { "" }
                    val problem = diagnose(log)
                    if (problem != null && problem != lastProblem) {
                        lastProblem = problem
                        appendLog("Server problem: $problem")
                        _message.value = "Connected, but " + problem
                    }
                }
            } catch (_: Throwable) { } finally { watching = false }
        }.apply { isDaemon = true; name = "routify-xray-watch" }.start()
    }

    private fun proxyPid(): Int? = try {
        File(dir, "proxy.pid").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
    } catch (_: Throwable) { null }

    /** True when [pid] is still our Xray (guards against a reused pid). */
    private fun isOurXray(pid: Int) = try {
        File("/proc/$pid/cmdline").readText().contains("libxray")
    } catch (_: Throwable) { false }

    private fun stopProxyProcess() {
        try { proxyProc?.destroy() } catch (_: Throwable) { }
        proxyProc = null
        // An Xray left over from before the app restarted would keep ports 8080 and 1080 busy.
        try {
            proxyPid()?.let { if (isOurXray(it)) android.os.Process.killProcess(it) }
            File(dir, "proxy.pid").delete()
        } catch (_: Throwable) { }
        try { XrayService.stop() } catch (_: Throwable) { }
    }

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
        val resolved = withContext(Dispatchers.IO) { pinIfNeeded(resolve(server)) }
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
            XRAY_LOCATION_ASSET=$d ${'$'}S nohup ${quote(lib)} run -c $d/config.json > $d/xray.log 2>&1 &
            echo ${'$'}! > $d/xray.pid
            sleep 1
            if ! kill -0 ${'$'}(cat $d/xray.pid) 2>/dev/null && ! grep -qE 'infra/conf|Failed to start' $d/xray.log 2>/dev/null; then
              XRAY_LOCATION_ASSET=$d ${'$'}S nohup ${linker()} ${quote(lib)} run -c $d/config.json >> $d/xray.log 2>&1 &
              echo ${'$'}! > $d/xray.pid
              sleep 1
            fi
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
        PhoneVpn.stop()
        stopPhoneEngine()
        val st = File(dir, "state").takeIf { it.exists() }?.readText()?.trim()
        // A leftover root session (state "running") must always be cleaned up with the root script, whatever the mode says now.
        if (proxyProc != null || st == "proxy" || (useProxy() && st != "running")) {
            stopProxyProcess()
            _running.value = false
            _message.value = "Stopped"
            try { File(dir, "state").writeText("stopped") } catch (_: Throwable) { }
            return
        }
        try { XrayService.stop() } catch (_: Throwable) { }
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
        proxyProc?.let { p ->
            if (p.isAlive) { _running.value = true; startWatcher(); return }
            proxyProc = null
        }
        var state = File(dir, "state").takeIf { it.exists() }?.readText()?.trim()
        if (state == "proxy") {
            val pid = proxyPid()
            if (pid != null && isOurXray(pid)) {
                _running.value = true
                _message.value = "Proxy on (HTTP $HTTP_PORT, SOCKS $SOCKS_PORT)"
                startWatcher()
                return
            }
            state = "stopped"
        }
        if (state == "running") {
            // After a reboot the state file stays behind (Xray and the iptables rules are gone). /proc is hidden from apps.
            val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            if (File(dir, "state").lastModified() < bootTime) state = "stopped"
        }
        _running.value = state == "running"
        if (state == "running") startWatcher()
        val prob = lastProblem
        _message.value = when (state) {
            "running" -> if (prob != null) "Connected, but " + prob else if (File(dir, "udp").takeIf { it.exists() }?.readText()?.trim() == "yes") "Connected (TCP, DNS and UDP)"
                else "Connected (TCP and DNS; this phone's kernel has no UDP proxy support)"
            "failed" -> "Failed to start. " + redact(File(dir, "xray.log").takeIf { it.exists() }?.readText()?.takeLast(300) ?: "")
            else -> "Stopped"
        }
    }
}

