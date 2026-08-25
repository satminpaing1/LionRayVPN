package com.lionray.vpn.core

import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.util.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the Xray-core JSON configuration:
 *  - inbound "socks": local proxy on 127.0.0.1 that hev-socks5-tunnel feeds
 *    with all captured device traffic (system-wide routing happens in
 *    LionRayVpnService + HevTunnel, not inside the core)
 *  - outbound "proxy": the selected VLESS server (TLS / REALITY / transports)
 *  - routing: depends on the selected mode:
 *      global     -> private ranges direct, everything else proxied
 *      split_cn   -> CN domains (+private, +domestic DNS) direct, rest proxied
 *      direct_all -> everything direct (diagnostic, tunnel acts as pass-through)
 */
object XrayConfigBuilder {

    /** CN domain patterns loaded from assets/cn_domains.txt by the service. */
    @Volatile
    var cnDomains: List<String> = emptyList()

    /** Ad/tracker blocklist loaded from assets/ad_domains.txt by the service. */
    @Volatile
    var adDomains: List<String> = emptyList()

    /** When true the first routing rule blocks every [adDomains] entry. */
    @Volatile
    var adBlock: Boolean = false

    /** User-defined domains that go DIRECT (phone IP) instead of proxy. */
    @Volatile
    var bypassDomains: List<String> = emptyList()

    /**
     * When true, VoIP call media (UDP) is carried THROUGH the tunnel using
     * Xray XUDP instead of going out direct — needed where Messenger/Viber/
     * Telegram are blocked at ISP level and direct UDP dies.
     */
    @Volatile
    var voipViaProxy: Boolean = false

    fun build(
        p: ServerProfile,
        socksPort: Int = 10808,
        routingMode: String = SettingsStore.MODE_GLOBAL,
        dnsDirectIps: List<String> = emptyList()
    ): String {
        val root = JSONObject()
        // info level needed so blocked-connection lines appear (ad counter)
        root.put(
            "log",
            JSONObject().put("loglevel", if (adBlock) "info" else "warning")
        )

        // ---------------- inbounds ----------------
        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray(listOf("http", "tls")))
            .put("routeOnly", false)

        val socksInbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put("auth", "noauth").put("udp", true)
            )
            .put("sniffing", sniffing)

        root.put("inbounds", JSONArray().put(socksInbound))

        // ---------------- outbounds ----------------
        val proxy: JSONObject = when (p.protocol.lowercase()) {

            // shadowsocks (Outline & friends) — password lives in uuid,
            // cipher in encryption; plain TCP transport, no TLS layer
            "ss" -> JSONObject()
                .put("tag", "proxy")
                .put("protocol", "shadowsocks")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", p.address)
                                .put("port", p.port)
                                .put("method", p.encryption.ifBlank { "aes-256-gcm" })
                                .put("password", p.uuid)
                        )
                    )
                )
                .put("streamSettings", streamSettings(p))

            // trojan — the secret is a password, TLS is its whole point
            "trojan" -> JSONObject()
                .put("tag", "proxy")
                .put("protocol", "trojan")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", p.address)
                                .put("port", p.port)
                                .put("password", p.uuid)
                        )
                    )
                )
                .put("streamSettings", streamSettings(p))

            else -> {
                val user = JSONObject()
                    .put("id", p.uuid)
                    .put("encryption", p.encryption.ifBlank { "none" })
                    .put("level", 0)
                if (p.flow.isNotBlank()) user.put("flow", p.flow)

                val vnext = JSONObject()
                    .put("address", p.address)
                    .put("port", p.port)
                    .put("users", JSONArray().put(user))

                JSONObject()
                    .put("tag", "proxy")
                    .put("protocol", "vless")
                    .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
                    .put("streamSettings", streamSettings(p))
            }
        }
        // XUDP (mux concurrency -1) encapsulates UDP over any transport —
        // mandatory when VoIP media must traverse ws+tls
        if (p.muxEnabled || voipViaProxy) {
            proxy.put("mux", JSONObject().put("enabled", true).put("concurrency", -1))
        }

        val direct = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")
            .put("settings", JSONObject())
        val block = JSONObject()
            .put("tag", "block")
            .put("protocol", "blackhole")
            .put("settings", JSONObject())

        root.put("outbounds", JSONArray().put(proxy).put(direct).put(block))

        // ---------------- routing ----------------
        val privateIps = JSONArray(
            listOf(
                "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                "169.254.0.0/16", "100.64.0.0/10", "224.0.0.0/3",
                "::1/128", "fc00::/7", "fe80::/10"
            )
        )
        val rules = JSONArray()

        // Ad/tracker blocker — must be the first rule so nothing else
        // short-circuits it
        if (adBlock && adDomains.isNotEmpty()) {
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(adDomains))
                    .put("outboundTag", "block")
            )
        }

        // User bypass list: these sites see the phone's own IP (fixes
        // sites that block Cloudflare / proxy exit addresses)
        if (bypassDomains.isNotEmpty()) {
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", JSONArray(bypassDomains))
                    .put("outboundTag", "direct")
            )
        }

        if (routingMode == SettingsStore.MODE_DIRECT) {
            rules.put(
                JSONObject().put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "direct")
            )
        } else {
            // domestic DNS servers resolve outside the tunnel so CN apps get
            // local CDN results
            if (dnsDirectIps.isNotEmpty()) {
                rules.put(
                    JSONObject().put("type", "field")
                        .put("ip", JSONArray(dnsDirectIps))
                        .put("outboundTag", "direct")
                )
            }
            if (routingMode == SettingsStore.MODE_SPLIT_CN && cnDomains.isNotEmpty()) {
                rules.put(
                    JSONObject().put("type", "field")
                        .put("domain", JSONArray(cnDomains))
                        .put("outboundTag", "direct")
                )
            }
            // Telegram group call media uses its own data centres; route
            // their UDP through the proxy so they survive ISP-level UDP
            // blocking (the classic "call joins but no audio" symptom).
            val telegramDomains = JSONArray(listOf(
                "domain:web.telegram.org",
                "domain:telegram.org",
                "domain:t.me",
                "domain:tg.dev"
            ))
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", telegramDomains)
                    .put("network", "udp")
                    .put("outboundTag", if (voipViaProxy) "proxy" else "direct")
            )
            // QUIC/HTTP3 over a CDN ws/tls tunnel is unreliable -> block it so
            // apps fall back to TCP (which proxies perfectly)
            // However, do NOT block UDP:443 for Telegram (group calls may use it)
            rules.put(
                JSONObject().put("type", "field")
                    .put("network", "udp")
                    .put("port", "443")
                    .put("outboundTag", "block")
            )
            // ws/tls transports cannot carry raw UDP... UNLESS XUDP mux is
            // active (voipViaProxy): then UDP is TCP-encapsulated through the
            // tunnel, which is required where the apps themselves are blocked
            if (!voipViaProxy) {
                // send UDP straight out so VoIP calls stay alive on normal
                // networks (Messenger / Telegram / Viber media)
                rules.put(
                    JSONObject().put("type", "field")
                        .put("network", "udp")
                        .put("outboundTag", "direct")
                )
            }
            rules.put(
                JSONObject().put("type", "field")
                    .put("ip", privateIps)
                    .put("outboundTag", "direct")
            )
            rules.put(
                JSONObject().put("type", "field")
                    .put("network", "tcp,udp")
                    .put("outboundTag", "proxy")
            )
        }

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", rules)
        )

        return root.toString(2)
    }

    private fun streamSettings(p: ServerProfile): JSONObject {
        val s = JSONObject().put("network", p.network)

        when (p.security.lowercase()) {
            "tls" -> {
                s.put("security", "tls")
                val tls = JSONObject()
                    .put("serverName", p.sni.ifBlank { p.host.ifBlank { p.address } })
                    .put("allowInsecure", p.allowInsecure)
                    .put("show", false)
                if (p.fingerprint.isNotBlank()) tls.put("fingerprint", p.fingerprint)
                if (p.alpn.isNotBlank()) {
                    val list = p.alpn.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    if (list.isNotEmpty()) tls.put("alpn", JSONArray(list))
                }
                // ClientHello fragmentation defeats SNI-based DPI throttling
                if (p.fragmentLength.isNotBlank() || p.fragmentPackets.isNotBlank()) {
                    tls.put(
                        "fragment",
                        JSONObject()
                            .put("packets", p.fragmentPackets.ifBlank { "tlshello" })
                            .put("length", p.fragmentLength.ifBlank { "40-60" })
                            .put("interval", p.fragmentInterval.ifBlank { "30-50" })
                    )
                }
                s.put("tlsSettings", tls)
            }
            "reality" -> {
                s.put("security", "reality")
                val reality = JSONObject()
                    .put("show", false)
                    .put("serverName", p.sni.ifBlank { p.host.ifBlank { p.address } })
                    .put("publicKey", p.publicKey)
                    .put("shortId", p.shortId)
                    .put("fingerprint", p.fingerprint.ifBlank { "chrome" })
                if (p.spiderX.isNotBlank()) reality.put("spiderX", p.spiderX)
                s.put("realitySettings", reality)
            }
        }

        when (p.network) {
            "tcp" -> {
                if (p.headerType == "http") {
                    val headers = JSONObject()
                    if (p.host.isNotBlank()) headers.put("Host", JSONArray(listOf(p.host)))
                    val request = JSONObject()
                        .put("version", "1.1")
                        .put("method", "GET")
                        .put("path", JSONArray(listOf(p.path.ifBlank { "/" })))
                        .put("headers", headers)
                    s.put(
                        "tcpSettings",
                        JSONObject().put(
                            "header",
                            JSONObject().put("type", "http").put("request", request)
                        )
                    )
                }
            }
            "ws" -> {
                val ws = JSONObject().put("path", p.path.ifBlank { "/" })
                if (p.host.isNotBlank()) ws.put("headers", JSONObject().put("Host", p.host))
                s.put("wsSettings", ws)
            }
            "grpc" -> {
                s.put(
                    "grpcSettings",
                    JSONObject()
                        .put("serviceName", p.serviceName)
                        .put("multiMode", false)
                )
            }
            "h2" -> {
                val hosts = if (p.host.isBlank()) JSONArray() else JSONArray(listOf(p.host))
                s.put(
                    "httpSettings",
                    JSONObject().put("path", p.path.ifBlank { "/" }).put("host", hosts)
                )
            }
            "httpupgrade" -> {
                s.put(
                    "httpupgradeSettings",
                    JSONObject().put("path", p.path.ifBlank { "/" }).put("host", p.host)
                )
            }
            "xhttp" -> {
                s.put(
                    "xhttpSettings",
                    JSONObject().put("path", p.path.ifBlank { "/" }).put("host", p.host)
                )
            }
            "splithttp" -> {
                s.put(
                    "splithttpSettings",
                    JSONObject().put("path", p.path.ifBlank { "/" }).put("host", p.host)
                )
            }
            "kcp" -> {
                val kcp = JSONObject()
                    .put("mtu", 1350)
                    .put("tti", 50)
                    .put("uplinkCapacity", 5)
                    .put("downlinkCapacity", 20)
                    .put("congestion", false)
                    .put("readBufferSize", 2)
                    .put("writeBufferSize", 2)
                    .put("header", JSONObject().put("type", p.headerType.ifBlank { "none" }))
                if (p.seed.isNotBlank()) kcp.put("seed", p.seed)
                s.put("kcpSettings", kcp)
            }
            "quic" -> {
                s.put(
                    "quicSettings",
                    JSONObject()
                        .put("security", "none")
                        .put("key", "")
                        .put("header", JSONObject().put("type", p.headerType.ifBlank { "none" }))
                )
            }
        }
        return s
    }
}
