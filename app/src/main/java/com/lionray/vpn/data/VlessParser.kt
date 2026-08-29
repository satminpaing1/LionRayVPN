package com.lionray.vpn.data

import android.net.Uri
import android.util.Base64

/**
 * Parses share links into a [ServerProfile]:
 *
 *   vless://   full VLESS params (TLS / REALITY / transports / fragment)
 *   trojan://  trojan://password@host:port?type=ws&sni=...&path=...#name
 *              TLS is implied unless security=none
 *   ss://      Shadowsocks — both SIP002
 *              (ss://base64url(method:password)@host:port#name) and the
 *              legacy all-base64 format. Plain AEAD only: links carrying an
 *              obfs/v2ray-plugin are rejected because Xray-core has no
 *              plugin support.
 */
object VlessParser {

    private val schemes = listOf("vless://", "trojan://", "ss://")

    fun parse(rawIn: String): ServerProfile? {
        val raw = rawIn.trim()
        return when {
            raw.startsWith("vless://") -> parseVless(raw)
            raw.startsWith("trojan://") -> parseTrojan(raw)
            raw.startsWith("ss://") -> parseSs(raw)
            else -> null
        }
    }

    /** First supported share link found inside arbitrary clipboard text. */
    fun extractUri(text: String): String? =
        text.lines().orEmpty().firstOrNull { l ->
            val t = l.trim().lowercase()
            schemes.any { t.startsWith(it) }
        }?.trim()

    fun isKnownScheme(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith("vless://") || t.startsWith("vmess://") ||
            t.startsWith("trojan://") || t.startsWith("ss://")
    }

    // ------------------------------------------------------------ vless

    private fun parseVless(raw: String): ServerProfile? = try {
        val uri = Uri.parse(raw)
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        fun qp(vararg keys: String): String {
            for (k in keys) {
                val v = uri.getQueryParameter(k)
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }

        var net = qp("type", "network", "method").lowercase().ifBlank { "tcp" }
        net = when (net) {
            "http" -> "h2"
            "websocket" -> "ws"
            "gprc" -> "grpc"   // common typo in the wild
            else -> net
        }
        var sec = qp("security").lowercase()
        if (sec.isBlank()) sec = if (qp("pbk", "publicKey").isNotEmpty()) "reality" else "none"

        // TLS fragmentation params, e.g. fragment=1,40-60,30-50,tlshello
        // (order-tolerant: picks the numeric ranges and the packets token)
        var fragPkts = ""
        var fragLen = ""
        var fragIvl = ""
        val fragRaw = qp("fragment", "fragments")
        if (fragRaw.isNotBlank() && fragRaw != "0" && fragRaw != "false") {
            val parts = fragRaw.split(",").map { it.trim() }
            val ranges = parts.filter { it.contains('-') && !it.equals("tlshello", true) }
            if (ranges.isNotEmpty()) fragLen = ranges[0]
            if (ranges.size > 1) fragIvl = ranges[1]
            fragPkts = parts.firstOrNull { it.equals("tlshello", true) } ?: ""
        }

        ServerProfile(
            id = 0L,
            protocol = "vless",
            remark = uri.fragment ?: "",
            address = host,
            port = port,
            uuid = uuid,
            encryption = qp("encryption").ifBlank { "none" },
            flow = qp("flow"),
            network = net,
            headerType = qp("headerType"),
            host = qp("host"),
            path = qp("path"),
            serviceName = qp("serviceName"),
            seed = qp("seed"),
            security = sec,
            sni = qp("sni", "peer", "sname"),
            fingerprint = qp("fp", "fingerprint"),
            publicKey = qp("pbk", "publicKey"),
            shortId = qp("sid", "shortId"),
            spiderX = qp("spx", "spiderX"),
            alpn = qp("alpn"),
            allowInsecure = qp("allowInsecure", "insecure") in listOf("1", "true"),
            fragmentPackets = fragPkts,
            fragmentLength = fragLen,
            fragmentInterval = fragIvl
        )
    } catch (t: Throwable) {
        null
    }

    // ----------------------------------------------------------- trojan

    private fun parseTrojan(raw: String): ServerProfile? = try {
        val uri = Uri.parse(raw)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null

        fun qp(vararg keys: String): String {
            for (k in keys) {
                val v = uri.getQueryParameter(k)
                if (!v.isNullOrEmpty()) return v
            }
            return ""
        }

        var net = qp("type").lowercase().ifBlank { "tcp" }
        net = when (net) {
            "http" -> "h2"
            "websocket" -> "ws"
            else -> net
        }
        val sec = qp("security").lowercase().ifBlank { "tls" }

        ServerProfile(
            id = 0L,
            protocol = "trojan",
            remark = Uri.decode(uri.fragment ?: ""),
            address = host,
            port = if (uri.port > 0) uri.port else 443,
            uuid = Uri.decode(password),
            encryption = "",
            network = net,
            headerType = qp("headerType"),
            host = qp("host"),
            path = qp("path"),
            serviceName = qp("serviceName"),
            security = sec,
            sni = qp("sni", "peer"),
            fingerprint = qp("fp"),
            alpn = qp("alpn"),
            allowInsecure = qp("allowInsecure", "insecure") in listOf("1", "true")
        )
    } catch (t: Throwable) {
        null
    }

    // --------------------------------------------------------------- ss

    private fun parseSs(rawIn: String): ServerProfile? = runCatching {
        val name = Uri.decode(rawIn.substringAfter('#', ""))
        val main = rawIn.removePrefix("ss://").substringBefore('#').substringBefore('?')

        val method: String
        val password: String
        val host: String
        val port: Int

        if (main.contains('@')) {
            // SIP002 — userinfo may be base64url(method:password) or plain
            var userinfo = main.substringBeforeLast('@')
            val hostport = main.substringAfterLast('@')
            userinfo = Uri.decode(userinfo)
            if (!userinfo.contains(':')) {
                userinfo = b64Decode(userinfo) ?: return@runCatching null
            }
            method = userinfo.substringBefore(':')
            password = userinfo.substringAfter(':', "")
            host = hostport.substringBeforeLast(':')
            port = hostport.substringAfterLast(':').trim().toIntOrNull() ?: 443
        } else {
            // legacy — whole thing is base64(method:password@host:port)
            val dec = b64Decode(main) ?: return@runCatching null
            val cred = dec.substringBeforeLast('@')
            val hostport = dec.substringAfterLast('@')
            method = cred.substringBefore(':')
            password = cred.substringAfter(':', "")
            host = hostport.substringBeforeLast(':')
            port = hostport.substringAfterLast(':').trim().toIntOrNull() ?: 443
        }

        if (host.isBlank() || method.isBlank() || password.isBlank()) return@runCatching null

        // Parse SIP003 plugin options, e.g.
        //   plugin=v2ray-plugin;mode=websocket;host=...;path=/;tls;mux=0
        val plugin = rawIn
            .substringAfter("plugin=", "")
            .substringBefore('&')
            .trim()
            .let { Uri.decode(it) }
        val isWsPlugin = plugin.contains("mode=websocket", ignoreCase = true)
        val pluginHasTls = Regex("(^|;)\\s*tls\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(plugin)
        val pluginHost = Regex("host=([^;]+)").find(plugin)
            ?.groupValues?.get(1)?.trim().orEmpty()
        val pluginPathRaw = Regex("path=([^;]+)").find(plugin)
            ?.groupValues?.get(1)?.trim()?.let { Uri.decode(it) }.orEmpty()
        // The "?enc=..." suffix is an EdgeTunnel marker, not part of the WS path.
        val wsPath = pluginPathRaw.substringBefore("?enc=").ifBlank { "/" }

        // EdgeTunnel / Cloudflare-Workers nodes publish "ss://" links whose
        // password is actually a VLESS UUID carried over WebSocket+TLS. Those
        // workers implement VLESS, not real Shadowsocks, so build a VLESS outbound.
        if (isWsPlugin && pluginHasTls && REGEX_UUID.matches(password)) {
            val customHost = pluginHost.ifBlank { host }
            return@runCatching ServerProfile(
                id = 0L,
                protocol = "vless",
                remark = name,
                address = host,
                port = port,
                uuid = password,
                encryption = "none",
                network = "ws",
                host = customHost,
                path = wsPath,
                security = "tls",
                sni = customHost,
                fingerprint = "chrome"
            )
        }

        // Any non-websocket plugin (obfs-local, simple-tls, ...) is unsupported
        // by Xray — reject it the same way a plain IP-filter would.
        val unsupportedPlugin = rawIn.contains("plugin=", ignoreCase = true) &&
            plugin.isNotBlank() && !plugin.equals("none", true) && !isWsPlugin
        if (unsupportedPlugin) return@runCatching null

        ServerProfile(
            id = 0L,
            protocol = "ss",
            remark = name,
            address = host,
            port = port,
            uuid = password,
            encryption = method.trim().lowercase(),
            network = if (isWsPlugin) "ws" else "tcp",
            host = pluginHost,
            path = if (isWsPlugin) wsPath else "",
            security = if (pluginHasTls) "tls" else "none",
            sni = if (pluginHasTls) (pluginHost.ifBlank { host }) else "",
            fingerprint = if (pluginHasTls) "chrome" else ""
        )
    }.getOrNull()

    private val REGEX_UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /** Tolerant base64: url-safe or standard, missing padding ok. */
    private fun b64Decode(s: String): String? = runCatching {
        val std = s.replace('-', '+').replace('_', '/').replace(" ", "")
        val padded = std + "====".substring(0, (4 - std.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT))
    }.getOrNull()
}
