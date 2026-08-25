package com.lionray.vpn.data

import android.net.Uri
import org.json.JSONObject

/**
 * One VLESS server entry. Every field of the vless:// share link is stored here
 * so it can be fully edited later.
 */
data class ServerProfile(
    var id: Long = 0L,
    // proxy protocol: vless | trojan | ss (shadowsocks)
    var protocol: String = "vless",
    var remark: String = "",
    var address: String = "",
    var port: Int = 443,
    var uuid: String = "",
    // subscription this server belongs to (0 = manually added)
    var subId: Long = 0L,
    // user settings
    var encryption: String = "none",
    var flow: String = "",
    // transport
    var network: String = "tcp",          // tcp, ws, grpc, h2, httpupgrade, xhttp, splithttp, kcp, quic
    var headerType: String = "",          // tcp/kcp/quic header: "" or http / none / ...
    var host: String = "",
    var path: String = "",
    var serviceName: String = "",
    var seed: String = "",
    // security: none | tls | reality
    var security: String = "none",
    var sni: String = "",
    var fingerprint: String = "",
    var publicKey: String = "",
    var shortId: String = "",
    var spiderX: String = "",
    var alpn: String = "",
    var allowInsecure: Boolean = false,
    var muxEnabled: Boolean = false,
    // TLS ClientHello fragmentation (DPI bypass): e.g. tlshello / 40-60 / 30-50
    var fragmentPackets: String = "",
    var fragmentLength: String = "",
    var fragmentInterval: String = "",
    // ISO-3166 alpha-2 country code ("" until resolved), drives the flag emoji
    var countryCode: String = ""
) {

    fun displayName(): String = remark.ifBlank { address }

    /** 🇸🇬-style regional-indicator emoji from the 2-letter country code.
     *  Currently NOT shown anywhere — kept for a future opt-in toggle
     *  because entry-IP GeoIP mislabels CDN-fronted servers. */
    fun flagEmoji(): String {
        val cc = countryCode.trim().uppercase()
        if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return ""
        return buildString {
            for (c in cc) append(Character.toChars(0x1F1E6 + (c - 'A')))
        }
    }

    fun displayAddress(): String = "$address:$port"

    /** Hides the host so bystanders cannot read the real server IP. */
    fun maskedAddress(): String = "***:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("subId", subId)
        put("protocol", protocol)
        put("remark", remark)
        put("address", address)
        put("port", port)
        put("uuid", uuid)
        put("encryption", encryption)
        put("flow", flow)
        put("network", network)
        put("headerType", headerType)
        put("host", host)
        put("path", path)
        put("serviceName", serviceName)
        put("seed", seed)
        put("security", security)
        put("sni", sni)
        put("fingerprint", fingerprint)
        put("publicKey", publicKey)
        put("shortId", shortId)
        put("spiderX", spiderX)
        put("alpn", alpn)
        put("allowInsecure", allowInsecure)
        put("muxEnabled", muxEnabled)
        put("fragmentPackets", fragmentPackets)
        put("fragmentLength", fragmentLength)
        put("fragmentInterval", fragmentInterval)
        put("countryCode", countryCode)
    }

    fun toShareUri(): String {
        fun enc(v: String): String = Uri.encode(v)
        val fragment = "#${enc(remark.ifBlank { "LionRay" })}"

        // ---- shadowsocks (SIP002) ----
        if (protocol.equals("ss", true)) {
            val cred = android.util.Base64.encodeToString(
                "${encryption.ifBlank { "aes-256-gcm" }}:$uuid".toByteArray(),
                android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_WRAP or
                    android.util.Base64.NO_PADDING
            )
            return "ss://$cred@$address:$port$fragment"
        }

        // ---- trojan (TLS implied) ----
        if (protocol.equals("trojan", true)) {
            val q = ArrayList<String>()
            if (!security.equals("none", true)) q.add("security=${enc(security)}")
            addParam(q, "type", network)
            addParam(q, "host", host)
            if (network != "tcp" && path.isNotEmpty()) addParam(q, "path", path)
            addParam(q, "serviceName", serviceName)
            addParam(q, "sni", sni)
            addParam(q, "fp", fingerprint)
            addParam(q, "alpn", alpn)
            if (allowInsecure) q.add("allowInsecure=1")
            val query = if (q.isEmpty()) "" else "?" + q.joinToString("&")
            return "trojan://${enc(uuid)}@$address:$port$query$fragment"
        }

        // ---- vless ----
        val q = ArrayList<String>()
        fun add(key: String, value: String) {
            if (value.isNotEmpty()) q.add("$key=${enc(value)}")
        }
        add("encryption", encryption.ifBlank { "none" })
        add("flow", flow)
        add("security", security)
        add("sni", sni)
        add("sname", sni)
        add("alpn", alpn)
        add("fp", fingerprint)
        if (security == "reality") {
            add("pbk", publicKey)
            add("sid", shortId)
            add("spx", spiderX)
        }
        add("type", network)
        add("headerType", headerType)
        add("host", host)
        if (network != "tcp" && path.isNotEmpty()) q.add("path=${enc(path)}")
        if (network == "grpc") add("serviceName", serviceName)
        if (network == "kcp" && seed.isNotEmpty()) q.add("seed=${enc(seed)}")
        if (allowInsecure) q.add("allowInsecure=1")
        val query = if (q.isEmpty()) "" else "?" + q.joinToString("&")
        return "vless://$uuid@$address:$port$query$fragment"
    }

    private fun addParam(q: MutableList<String>, key: String, value: String) {
        if (value.isNotEmpty()) q.add("$key=${Uri.encode(value)}")
    }

    companion object {
        fun fromJson(o: JSONObject): ServerProfile = ServerProfile(
            id = o.optLong("id", 0L),
            subId = o.optLong("subId", 0L),
            protocol = o.optString("protocol", "vless").ifBlank { "vless" },
            remark = o.optString("remark"),
            address = o.optString("address"),
            port = o.optInt("port", 443),
            uuid = o.optString("uuid"),
            encryption = o.optString("encryption", "none").ifBlank { "none" },
            flow = o.optString("flow"),
            network = o.optString("network", "tcp").ifBlank { "tcp" },
            headerType = o.optString("headerType"),
            host = o.optString("host"),
            path = o.optString("path"),
            serviceName = o.optString("serviceName"),
            seed = o.optString("seed"),
            security = o.optString("security", "none").ifBlank { "none" },
            sni = o.optString("sni"),
            fingerprint = o.optString("fingerprint"),
            publicKey = o.optString("publicKey"),
            shortId = o.optString("shortId"),
            spiderX = o.optString("spiderX"),
            alpn = o.optString("alpn"),
            allowInsecure = o.optBoolean("allowInsecure", false),
            muxEnabled = o.optBoolean("muxEnabled", false),
            fragmentPackets = o.optString("fragmentPackets"),
            fragmentLength = o.optString("fragmentLength"),
            fragmentInterval = o.optString("fragmentInterval"),
            countryCode = o.optString("countryCode")
        )
    }
}

/**
 * A subscription URL whose content is a list of vless:// keys
 * (plain text or base64-encoded), refreshed periodically.
 */
data class Subscription(
    var id: Long = 0L,
    var remark: String = "",
    var url: String = "",
    var autoUpdate: Boolean = true,
    var lastUpdated: Long = 0L
) {
    fun displayName(): String = remark.ifBlank {
        try {
            java.net.URI(url).host ?: url
        } catch (t: Throwable) {
            url
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("remark", remark)
        put("url", url)
        put("autoUpdate", autoUpdate)
        put("lastUpdated", lastUpdated)
    }

    companion object {
        fun fromJson(o: JSONObject): Subscription = Subscription(
            id = o.optLong("id", 0L),
            remark = o.optString("remark"),
            url = o.optString("url"),
            autoUpdate = o.optBoolean("autoUpdate", true),
            lastUpdated = o.optLong("lastUpdated", 0L)
        )
    }
}
