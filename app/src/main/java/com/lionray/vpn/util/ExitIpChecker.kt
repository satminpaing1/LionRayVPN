package com.lionray.vpn.util

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Detects the REAL public exit IP of the tunnel.
 *
 * The app itself is excluded from the TUN (addDisallowedApplication), so a
 * plain HTTP request would bypass the VPN and show the ISP address. To get
 * the honest answer the request is sent through the local Xray SOCKS5
 * inbound (127.0.0.1:<socksPort>) which routes it exactly like user traffic.
 */
object ExitIpChecker {

    data class ExitInfo(val ip: String, val country: String?, val code: String?)

    fun fetch(socksPort: Int): ExitInfo? = viaIpWhoIs(socksPort) ?: viaIpApi(socksPort)

    private fun viaIpWhoIs(port: Int): ExitInfo? = try {
        val body = get("https://ipwho.is/", port) ?: return null
        val j = JSONObject(body)
        val ok = j.optBoolean("success", true)
        val ip = j.optString("ip").trim()
        if (!ok || ip.isEmpty()) null
        else ExitInfo(
            ip,
            j.optString("country").takeIf { it.isNotBlank() },
            j.optString("country_code").takeIf { it.isNotBlank() }
        )
    } catch (t: Throwable) {
        null
    }

    private fun viaIpApi(port: Int): ExitInfo? = try {
        val body = get(
            "http://ip-api.com/json/?fields=status,country,countryCode,query",
            port
        ) ?: return null
        val j = JSONObject(body)
        val ip = j.optString("query").trim()
        if (!"ok".equals(j.optString("status"), true) || ip.isEmpty()) null
        else ExitInfo(
            ip,
            j.optString("country").takeIf { it.isNotBlank() },
            j.optString("countryCode").takeIf { it.isNotBlank() }
        )
    } catch (t: Throwable) {
        null
    }

    private fun get(urlStr: String, socksPort: Int): String? = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "LionRayVPN")
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        null
    }

    /** Converts an ISO country code ("JP") into a flag emoji. */
    fun flagEmoji(code: String?): String {
        if (code.isNullOrEmpty() || code.length != 2) return "\uD83C\uDF10"
        val upper = code.uppercase()
        for (c in upper) if (c !in 'A'..'Z') return "\uD83C\uDF10"
        return buildString {
            for (c in upper) append(Character.toChars(0x1F1E6 + (c - 'A')))
        }
    }
}
