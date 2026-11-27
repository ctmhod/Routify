package be.mygod.vpnhotspot.xray

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import be.mygod.vpnhotspot.App.Companion.app
import hev.htproxy.TProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Puts this phone's own traffic on the VPN too, next to sharing it with other devices. */
object PhoneVpn {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> get() = _status
    fun setStatus(s: String) { _status.value = s }

    /** Starts the VPN interface. [port] is the local SOCKS port of the Xray engine this phone uses. */
    fun begin(port: Int, allowList: Boolean = false) {
        app.startService(Intent(app, PhoneVpnService::class.java).putExtra("port", port).putExtra("allow", allowList))
    }

    fun stop() {
        try { PhoneVpnService.instance?.shutdown() } catch (_: Throwable) { }
        try { app.stopService(Intent(app, PhoneVpnService::class.java)) } catch (_: Throwable) { }
        _status.value = ""
    }

    /**
     * Sends one small web request through the local SOCKS port and waits for any answer, so a dead or misconfigured
     * server is found before this device is put on the VPN. Returns an empty string when it works, else the reason.
     */
    fun serverCheck(port: Int): String {
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 3000)
                s.soTimeout = 12000
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                val r = ByteArray(2)
                if (i.read(r) != 2 || r[1].toInt() != 0) return "the local proxy did not answer"
                o.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0, 80)); o.flush()
                val rep = ByteArray(10)
                var n = 0
                while (n < 10) { val k = i.read(rep, n, 10 - n); if (k < 0) return "the server closed the connection"; n += k }
                if (rep[1].toInt() != 0) return "the server could not reach the internet (code ${rep[1]})"
                o.write("GET / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n".toByteArray()); o.flush()
                val b = ByteArray(16)
                val k = i.read(b)
                return if (k > 0) "" else "the server did not send any data back"
            }
        } catch (e: java.net.SocketTimeoutException) {
            return "the server did not answer in time"
        } catch (e: Throwable) {
            return "test connection failed (${e.javaClass.simpleName})"
        }
    }

    /** True when Android still needs to show its one-time "connection request" box. */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    /** 0.0.0.0/0 minus private and local ranges, as CIDR blocks, so devices on the local network stay reachable. */
    fun routes(): List<Pair<String, Int>> {
        fun ip(s: String): Long = s.split('.').fold(0L) { a, p -> a * 256 + p.toLong() }
        val excluded = listOf("10.0.0.0" to 8, "127.0.0.0" to 8, "169.254.0.0" to 16, "172.16.0.0" to 12,
            "192.168.0.0" to 16, "224.0.0.0" to 4)
        var ranges = listOf(0L to 0xFFFFFFFFL)
        for ((base, len) in excluded) {
            val s = ip(base)
            val e = s + (1L shl (32 - len)) - 1
            ranges = ranges.flatMap { (a, b) ->
                if (e < a || s > b) listOf(a to b) else buildList {
                    if (s > a) add(a to s - 1)
                    if (e < b) add(e + 1 to b)
                }
            }
        }
        val out = ArrayList<Pair<String, Int>>()
        for ((a0, b) in ranges) {
            var a = a0
            while (a <= b) {
                var size = if (a == 0L) 1L shl 32 else a and -a
                while (a + size - 1 > b) size = size shr 1
                val len = 32 - java.lang.Long.numberOfTrailingZeros(size)
                out.add("${(a shr 24) and 255}.${(a shr 16) and 255}.${(a shr 8) and 255}.${a and 255}" to len)
                a += size
            }
        }
        return out
    }
}

class PhoneVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    companion object {
        @Volatile var instance: PhoneVpnService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** Stops the tunnel and removes the VPN interface for sure, even if the helper library is slow to stop. */
    fun shutdown() {
        stopTunnel()
        PhoneVpn.setStatus("")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", XrayController.SOCKS_PORT) ?: XrayController.SOCKS_PORT
        try {
            startTunnel(port, intent?.getBooleanExtra("allow", false) == true ||
                app.getSharedPreferences("routify_xray", MODE_PRIVATE).getBoolean("force_allowlist", false))
        } catch (e: Throwable) {
            XrayController.appendLog("Phone VPN failed: ${e.javaClass.simpleName} ${e.message}")
            PhoneVpn.setStatus("This device is not on the VPN (${e.javaClass.simpleName}). Sharing still works.")
            stopTunnel()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(port: Int, allowList: Boolean) {
        stopTunnel()
        val b = Builder().setSession("Routify").setMtu(1500)
            .addAddress("198.18.0.1", 32).addAddress("fc00::1", 128)
            .addDnsServer("198.18.0.2")
        if (allowList) {
            // Root mode: the sharing Xray runs as root, and root is not an app, so a list of apps keeps it out of the VPN
            // (otherwise hotspot traffic would be wrapped twice). Every installed app except Routify goes through the VPN.
            var n = 0
            for (a in packageManager.getInstalledApplications(0)) {
                if (a.packageName == packageName) continue
                try { b.addAllowedApplication(a.packageName); n++ } catch (_: Throwable) { }
            }
            XrayController.appendLog("Phone VPN: root mode, $n apps on the VPN")
            check(n > 0) { "no apps to route" }
        } else b.addDisallowedApplication(packageName)
        for ((ip, len) in PhoneVpn.routes()) b.addRoute(ip, len)
        b.addRoute("::", 0)
        val fd = b.establish() ?: error("Android refused the VPN interface")
        tun = fd
        val cfg = File(getDir("xray", MODE_PRIVATE), "tun.yml")
        cfg.writeText("""
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
            socks5:
              port: $port
              address: 127.0.0.1
              udp: 'udp'
              udp-address: 127.0.0.1
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-level: warn
        """.trimIndent() + "\n")
        check(TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)) { "tunnel did not start" }
        PhoneVpn.setStatus("This device is on the VPN too")
        XrayController.appendLog("Phone VPN on (SOCKS port $port)")
        try { XrayService.start() } catch (e: Throwable) { XrayController.appendLog("Background notification: ${e.javaClass.simpleName}") }
    }

    @Synchronized
    private fun stopTunnel() {
        // Stop the helper on its own thread so a slow stop can never keep the VPN interface alive.
        val t = Thread { try { TProxyService.TProxyStopService() } catch (_: Throwable) { } }
        t.start()
        try { t.join(3000) } catch (_: Throwable) { }
        try { tun?.close() } catch (_: Throwable) { }
        tun = null
        android.util.Log.i("RoutifyVpn", "tunnel stopped, helper thread alive=" + t.isAlive)
    }

    override fun onRevoke() {
        PhoneVpn.setStatus("This device left the VPN (another VPN app took over). Sharing still works.")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
