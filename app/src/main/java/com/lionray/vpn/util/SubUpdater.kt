package com.lionray.vpn.util

import android.content.Context
import android.widget.Toast
import com.lionray.vpn.R
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.data.SubStore
import com.lionray.vpn.data.Subscription
import com.lionray.vpn.data.VlessParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a subscription URL and imports the servers it contains.
 * The payload format is auto-detected ("adaptive"):
 *  - Sing-box JSON (outbounds array)
 *  - Clash / Clash.Meta YAML (proxies list)
 *  - Plain text list of vless:// links (one per line)
 *  - Base64 blob decoding to such a list
 */
object SubUpdater {

    /** Thrown when fetching/parsing fails; message is user-presentable. */
    class SubException(message: String) : Exception(message)

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw SubException("Invalid URL: $url")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "LionRayVPN/1.6")
            val code = conn.responseCode
            if (code !in 200..299) throw SubException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Smart entry point used by QR scan / clipboard import: if [raw] is an
     * http(s) URL it is treated as a subscription (created if new, fetched
     * immediately, all servers imported under it). Returns false when the
     * payload is not a URL so the caller can fall back to key import.
     */
    suspend fun smartImport(context: Context, raw: String): Boolean {
        val t = raw.trim()
        if (!t.startsWith("http://") && !t.startsWith("https://")) return false
        val host = try {
            java.net.URI(t).host ?: t
        } catch (e: Throwable) {
            t
        }
        toast(context, context.getString(R.string.subs_updating, host))
        var sub = SubStore.subs.value.firstOrNull { it.url == t }
        if (sub == null) {
            SubStore.upsert(Subscription(remark = "", url = t, autoUpdate = true))
            sub = SubStore.subs.value.firstOrNull { it.url == t }
        }
        if (sub == null) {
            toast(context, context.getString(R.string.import_failed))
            return true
        }
        return try {
            val n = update(sub.id)
            toast(context, context.getString(R.string.subs_updated_ok, sub.displayName(), n))
            true
        } catch (e: Throwable) {
            toast(context, context.getString(R.string.subs_update_fail, e.message ?: ""))
            true
        }
    }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** Decodes base64 subscription payloads; passes plain text through. */
    fun decodeIfBase64(raw: String): String {
        val t = raw.trim()
        if (t.contains("://")) return t
        return try {
            val bytes = Base64.decode(t, Base64.DEFAULT)
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.contains("://")) decoded else t
        } catch (e: Throwable) {
            raw
        }
    }

    /** Auto-detects the subscription format and parses every usable server. */
    fun parseProfiles(raw: String, subId: Long): List<ServerProfile> {
        val t = raw.trim()
        val parsed = when {
            t.startsWith("{") -> parseSingBox(t, subId)
            Regex("(?m)^\\s*proxies\\s*:\\s*(#.*)?$").containsMatchIn(t) ->
                parseClash(t, subId)
            else -> parseVlessLines(decodeIfBase64(t), subId)
        }
        return parsed
            .filter { it.address.isNotBlank() && it.uuid.isNotBlank() }
            .distinctBy { it.toShareUri() }
    }

    /** Plain-text / base64 payload: one vless:// link per line. */
    private fun parseVlessLines(text: String, subId: Long): List<ServerProfile> =
        text.lines()
            .mapNotNull { line ->
                val l = line.trim()
                if (!l.startsWith("vless://")) return@mapNotNull null
                val uri = VlessParser.extractUri(l) ?: return@mapNotNull null
                VlessParser.parse(uri)
            }
            .map { it.copy(subId = subId) }

    // ------------------------------------------------------------- sing-box

    private fun parseSingBox(raw: String, subId: Long): List<ServerProfile> = try {
        val root = org.json.JSONObject(raw)
        val arr = root.optJSONArray("outbounds")
            ?: return emptyList()
        val out = ArrayList<ServerProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type").lowercase()
            if (type != "vless" && type != "vmess" && type != "trojan") continue
            val p = ServerProfile()
            p.remark = o.optString("tag")
            p.address = o.optString("server")
            p.port = o.optInt("server_port", 443)
            p.uuid = o.optString(if (type == "trojan") "password" else "uuid")
            p.flow = o.optString("flow")
            p.security = "none"
            val tls = o.optJSONObject("tls")
            if (tls != null && tls.optBoolean("enabled")) {
                p.security = "tls"
                p.sni = tls.optString("server_name")
                p.alpn = tls.optString("alpn")
                p.allowInsecure = tls.optBoolean("insecure")
                val reality = tls.optJSONObject("reality")
                if (reality != null && reality.optBoolean("enabled")) {
                    p.security = "reality"
                    p.publicKey = reality.optString("public_key")
                    p.shortId = reality.optString("short_id")
                }
            }
            applyTransport(p, o.optJSONObject("transport"))
            out.add(p.copy(subId = subId))
        }
        out
    } catch (e: Throwable) {
        emptyList()
    }

    private fun applyTransport(p: ServerProfile, tr: org.json.JSONObject?) {
        if (tr == null) return
        when (tr.optString("type").lowercase()) {
            "ws" -> {
                p.network = "ws"
                p.path = tr.optString("path")
                p.host = tr.optJSONObject("headers")?.optString("Host").orEmpty()
            }
            "httpupgrade" -> {
                p.network = "httpupgrade"
                p.path = tr.optString("path")
                p.host = tr.optJSONObject("headers")?.optString("Host").orEmpty()
            }
            "grpc" -> {
                p.network = "grpc"
                p.serviceName = tr.optString("service_name")
            }
            "http" -> {
                p.network = "h2"
                p.path = tr.optString("path")
                p.host = tr.optJSONObject("headers")?.optString("Host").orEmpty()
            }
            "quic" -> {
                p.network = "quic"
                p.path = tr.optString("path")
            }
        }
    }

    // ----------------------------------------------------------- clash yaml

    private fun parseClash(raw: String, subId: Long): List<ServerProfile> {
        val out = ArrayList<ServerProfile>()
        val lines = raw.lines()
        var i = 0
        while (i < lines.size) {
            if (Regex("^proxies\\s*:").matches(lines[i].trim())) {
                val baseIndent = leadingSpaces(lines[i])
                i++
                val cur = StringBuilder()
                fun flush() {
                    if (cur.isNotBlank()) {
                        clashEntry(cur.toString())?.let { out.add(it.copy(subId = subId)) }
                    }
                    cur.setLength(0)
                }
                while (i < lines.size) {
                    val l = lines[i]
                    val lt = l.trim()
                    if (lt.isEmpty() || lt.startsWith("#")) { i++; continue }
                    if (leadingSpaces(l) <= baseIndent && !lt.startsWith("- ")) break
                    if (lt.startsWith("- ")) {
                        flush()
                        cur.append(lt.removePrefix("- "))
                    } else {
                        cur.append('\n').append(lt)
                    }
                    i++
                }
                flush()
                break
            }
            i++
        }
        return out
    }

    private fun leadingSpaces(s: String): Int {
        var n = 0
        while (n < s.length && s[n] == ' ') n++
        return n
    }

    /** Maps one Clash proxy entry onto our VLESS profile where possible. */
    private fun clashEntry(block: String): ServerProfile? {
        fun g(pattern: String): String? =
            Regex(pattern).find(block)?.groupValues?.get(1)?.trim()?.trim('"', '\'')

        val type = g("\\btype\\s*:\\s*\"?(vless|vmess|trojan)\\b")?.lowercase()
            ?: return null
        val p = ServerProfile()
        p.remark = g("\\bname\\s*:\\s*\"?([^\",}\n]+)\"?") ?: ""
        p.address = g("\\bserver\\s*:\\s*\"?([^\",}\n]+)\"?") ?: ""
        p.port = g("\\bport\\s*:\\s*(\\d+)")?.toIntOrNull() ?: 443
        p.uuid = g("\\buuid\\s*:\\s*\"?([^\",}\n]+)\"?")
            ?: g("\\bpassword\\s*:\\s*\"?([^\",}\n]+)\"?")
            ?: ""
        p.network = (g("\\bnetwork\\s*:\\s*\"?(\\w+)") ?: "tcp").lowercase()
        if (p.network !in setOf(
                "tcp", "ws", "grpc", "h2", "httpupgrade",
                "xhttp", "splithttp", "kcp", "quic"
            )
        ) p.network = "tcp"

        val pbk = g("public-key\\s*:\\s*\"?([A-Za-z0-9+/=_\\-]+)")
        if (pbk != null) {
            p.security = "reality"
            p.publicKey = pbk
            p.shortId = g("short-id\\s*:\\s*\"?([A-Za-z0-9]+)\"?") ?: ""
        } else if (g("\\btls\\s*:\\s*true") != null) {
            p.security = "tls"
        }
        p.flow = g("\\bflow\\s*:\\s*\"?([\\w-]+)") ?: ""
        p.sni = g("(?:\\bservername|\\bsni)\\s*:\\s*\"?([^\",}\n]+)\"?") ?: ""
        g("\\bclient-fingerprint\\s*:\\s*\"?([\\w-]+)")?.let { p.fingerprint = it }
        if (g("skip-cert-verify\\s*:\\s*true") != null) p.allowInsecure = true
        p.path = g("path\\s*:\\s*\"?([^\",}\n]+)\"?") ?: ""
        p.serviceName = g("grpc-service-name\\s*:\\s*\"?([^\",}\n]+)\"?") ?: ""
        p.host = g("[Hh]ost\\s*:\\s*\"?([^\",}\n]+)\"?") ?: ""

        if (type == "trojan" && p.security == "none") p.security = "tls"
        return p
    }

    /**
     * Fetches + parses + replaces all servers of [subId].
     * Returns the number of imported servers.
     */
    suspend fun update(subId: Long): Int {
        val sub = SubStore.get(subId) ?: throw SubException("Subscription not found")
        val raw = fetch(sub.url)
        val list = parseProfiles(raw, subId)
        if (list.isEmpty()) {
            throw SubException(
                "No supported servers found in subscription " +
                    "(vless / clash / sing-box)"
            )
        }
        ProfileStore.replaceSub(subId, list)
        SubStore.markUpdated(subId, System.currentTimeMillis())
        return list.size
    }
}
