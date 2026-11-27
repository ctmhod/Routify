package be.mygod.vpnhotspot.xray

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** A VLESS or VMess server parsed from a share link. Own implementation, written from the public link formats. */
data class XrayServer(
    val protocol: String,        // "vless" or "vmess"
    val name: String,
    val host: String,
    val port: Int,
    val id: String,              // uuid
    val network: String = "tcp", // tcp, ws, grpc
    val security: String = "none", // none, tls, reality
    val sni: String = "",
    val wsHost: String = "",     // Host header for ws
    val path: String = "",       // ws path or grpc serviceName
    val flow: String = "",
    val alpn: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val alterId: Int = 0,
    val vmessCipher: String = "auto",
    val insecure: Boolean = false, // allowInsecure=1 in the link (Routify pins the server certificate instead)
    val pin: String = "",          // pcs= in the link: SHA-256 of the server certificate
    val hostName: String = "",     // the server's own DNS name (kept when [host] is replaced by its IP)
) {
    /** Only xtls-rprx-vision is a real flow, and only over plain TCP. Links that say flow=none must not send it to Xray. */
    private fun flowOk() = flow.startsWith("xtls-rprx-vision") && network == "tcp"

    /** Link settings for the log. Never includes the id. */
    fun describe(): String = buildString {
        append("$protocol $network/$security port=$port host=$host")
        if (sni.isNotEmpty()) append(" sni=$sni")
        if (wsHost.isNotEmpty()) append(" hostHeader=$wsHost")
        if (path.isNotEmpty()) append(" path=$path")
        if (alpn.isNotEmpty()) append(" alpn=$alpn")
        if (fingerprint.isNotEmpty()) append(" fp=$fingerprint")
        if (flow.isNotEmpty()) append(" flow=$flow(${if (flowOk()) "sent" else "not sent"})")
        if (insecure) append(" allowInsecure=1")
        if (pin.isNotEmpty()) append(" pin=link")
    }

    /** Outbound object for Xray's JSON config. */
    fun outbound(mark: Boolean = true): JSONObject {
        val stream = JSONObject().put("network", network).put("security", security)
        val tlsName = sni.ifEmpty { wsHost.ifEmpty { host } }
        when (security) {
            "tls" -> stream.put("tlsSettings", JSONObject().put("serverName", tlsName).apply {
                if (pin.isNotEmpty()) put("pinnedPeerCertSha256", pin)
                // WebSocket needs HTTP/1.1, so never offer h2 for it.
                val protos = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() && !(network == "ws" && it == "h2") }
                if (protos.isNotEmpty()) put("alpn", JSONArray(protos))
                if (fingerprint.isNotEmpty()) put("fingerprint", fingerprint)
            })
            "reality" -> stream.put("realitySettings", JSONObject().put("serverName", tlsName)
                .put("fingerprint", fingerprint.ifEmpty { "chrome" })
                .put("publicKey", publicKey).put("shortId", shortId))
        }
        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject().put("path", path.ifEmpty { "/" }).apply {
                if (wsHost.isNotEmpty()) put("headers", JSONObject().put("Host", wsHost))
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", path))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", path.ifEmpty { "/" }).apply {
                if (wsHost.isNotEmpty()) put("host", wsHost)
            })
            "xhttp", "splithttp" -> stream.put("network", "xhttp").put("xhttpSettings", JSONObject().put("path", path.ifEmpty { "/" }).apply {
                if (wsHost.isNotEmpty()) put("host", wsHost)
            })
        }
        if (mark) stream.put("sockopt", JSONObject().put("mark", 0x1000000))
        val settings = if (protocol == "vless") JSONObject().put("vnext", JSONArray().put(JSONObject()
            .put("address", host).put("port", port).put("users", JSONArray().put(JSONObject()
                .put("id", id).put("encryption", "none").apply { if (flowOk()) put("flow", flow) }))))
        else JSONObject().put("vnext", JSONArray().put(JSONObject()
            .put("address", host).put("port", port).put("users", JSONArray().put(JSONObject()
                .put("id", id).put("alterId", alterId).put("security", vmessCipher)))))
        return JSONObject().put("tag", "proxy").put("protocol", protocol)
            .put("settings", settings).put("streamSettings", stream)
    }

    companion object {
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
        /** Some generators encode twice, so a path like /vpnjantit arrives as %2Fvpnjantit. Servers never want a literal %XX in a path. */
        private fun fixEnc(s: String) = if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(s)) try { dec(s) } catch (_: Exception) { s } else s

        /** Returns null if the text is not a valid vless:// or vmess:// link. */
        fun parse(text: String): XrayServer? = try {
            val t = text.trim()
            when {
                t.startsWith("vless://", true) -> parseVless(t)
                t.startsWith("vmess://", true) -> parseVmess(t)
                else -> null
            }
        } catch (_: Exception) { null }

        private fun parseVless(t: String): XrayServer? {
            val u = URI(t.replace(" ", "%20"))
            val host = u.host ?: return null
            if (u.port !in 1..65535 || u.userInfo.isNullOrEmpty()) return null
            val q = (u.rawQuery ?: "").split('&').filter { '=' in it }
                .associate { dec(it.substringBefore('=')) to dec(it.substringAfter('=')) }
            return XrayServer("vless", dec(u.rawFragment ?: host), host, u.port, u.userInfo,
                network = (q["type"] ?: "tcp").let { if (it == "websocket") "ws" else it },
                security = q["security"] ?: "none", sni = fixEnc(q["sni"] ?: q["peer"] ?: ""),
                wsHost = fixEnc(q["host"] ?: ""), path = fixEnc(q["path"] ?: q["serviceName"] ?: ""),
                flow = q["flow"] ?: "", alpn = q["alpn"] ?: "", fingerprint = q["fp"] ?: "",
                publicKey = q["pbk"] ?: "", shortId = q["sid"] ?: "",
                insecure = (q["allowInsecure"] ?: q["insecure"]) in listOf("1", "true"),
                pin = q["pcs"] ?: "")
        }

        private fun parseVmess(t: String): XrayServer? {
            val raw = t.substring(8).trim()
            val j = JSONObject(String(Base64.decode(raw, Base64.DEFAULT or Base64.URL_SAFE), Charsets.UTF_8))
            val port = j.optString("port").toIntOrNull() ?: return null
            val host = j.optString("add").ifEmpty { return null }
            val id = j.optString("id").ifEmpty { return null }
            return XrayServer("vmess", j.optString("ps", host), host, port, id,
                network = j.optString("net", "tcp").let { if (it == "websocket") "ws" else it },
                security = if (j.optString("tls") == "tls") "tls" else "none",
                sni = j.optString("sni"), wsHost = j.optString("host"), path = j.optString("path"),
                alpn = j.optString("alpn"), fingerprint = j.optString("fp"),
                alterId = j.optString("aid").toIntOrNull() ?: 0,
                vmessCipher = j.optString("scy").ifEmpty { "auto" })
        }
    }
}

