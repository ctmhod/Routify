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
) {
    /** Outbound object for Xray's JSON config. */
    fun outbound(): JSONObject {
        val stream = JSONObject().put("network", network).put("security", security)
        val tlsName = sni.ifEmpty { wsHost.ifEmpty { host } }
        when (security) {
            "tls" -> stream.put("tlsSettings", JSONObject().put("serverName", tlsName).apply {
                if (alpn.isNotEmpty()) put("alpn", JSONArray(alpn.split(',')))
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
        }
        stream.put("sockopt", JSONObject().put("mark", 255))
        val settings = if (protocol == "vless") JSONObject().put("vnext", JSONArray().put(JSONObject()
            .put("address", host).put("port", port).put("users", JSONArray().put(JSONObject()
                .put("id", id).put("encryption", "none").apply { if (flow.isNotEmpty()) put("flow", flow) }))))
        else JSONObject().put("vnext", JSONArray().put(JSONObject()
            .put("address", host).put("port", port).put("users", JSONArray().put(JSONObject()
                .put("id", id).put("alterId", alterId).put("security", vmessCipher)))))
        return JSONObject().put("tag", "proxy").put("protocol", protocol)
            .put("settings", settings).put("streamSettings", stream)
    }

    companion object {
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

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
                security = q["security"] ?: "none", sni = q["sni"] ?: q["peer"] ?: "",
                wsHost = q["host"] ?: "", path = q["path"] ?: q["serviceName"] ?: "",
                flow = q["flow"] ?: "", alpn = q["alpn"] ?: "", fingerprint = q["fp"] ?: "",
                publicKey = q["pbk"] ?: "", shortId = q["sid"] ?: "")
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

