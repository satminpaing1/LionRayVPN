package com.lionray.vpn.core

import com.lionray.vpn.data.ServerProfile
import com.lionray.vpn.util.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the Xray-core JSON configuration:
 *  - inbound "tun": consumes the VpnService file descriptor passed to
 *    Libv2ray startLoop — this is how the WHOLE device's traffic enters the
 *    in-process core (no external hev-socks5-tunnel process needed).
 *  - inbound "socks": local proxy on 127.0.0.1 kept only so in-app IP/country
 *    checks (ExitIpChecker / GeoResolver) can observe the tunnel's real exit.
 *  - outbound "proxy": the selected VLESS server (TLS / REALITY / transports)
 *  - routing: private ranges direct, everything else through the proxy.
 *  - stats + policy: enable per-outbound byte counters used for the speed /
 *    usage read-out via CoreController.queryAllOutboundTrafficStats().
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
        socksPort: Int = XrayBridge.SOCKS_PORT,
        routingMode: String = SettingsStore.MODE_GLOBAL,
        dns: SettingsStore.Dns = SettingsStore.dnsPresets().first()
    ): String {
        val dnsDirectIps = if (dns.domestic) dns.servers else emptyList()
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "info"))

        // Built-in DNS module: intercepts the app's raw UDP:53 queries at the
        // TUN and re-resolves them over HTTPS/TCP (DoH) through the proxy.
        // CRITICAL for Cloudflare-fronted (vless-ws) servers which cannot relay
        // raw UDP through the WebSocket tunnel — without this, Viber/WhatsApp
        // system-DNS lookups silently die and messaging fails even though the
        // tunnel itself is fine.
        root.put(
            "dns",
            JSONObject()
                .put("queryStrategy", "UseIPv4")
                .put("disableCache", true)
                .put("servers", JSONArray(mixedDnsServers(dns.key)))
        )

        // Traffic byte counters for the speed / usage read-out. The in-process
        // core resets these on each query, so stats() returns per-interval deltas.
        root.put("stats", JSONObject())
        root.put(
            "policy",
            JSONObject().put(
                "system",
                JSONObject()
                    .put("statsOutboundUplink", true)
                    .put("statsOutboundDownlink", true)
            )
        )

        // ---------------- inbounds ----------------
        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
            .put("routeOnly", false)

        val socksInbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put("auth", "noauth").put("udp", true).put("userLevel", 8)
            )
            .put("sniffing", sniffing)

        // The fd handed to Libv2ray.startLoop is wired into this inbound.
        val tunInbound = JSONObject()
            .put("tag", "tun")
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "xray0")
                    .put("MTU", XrayBridge.MTU)
                    .put("userLevel", 8)
            )
            .put("sniffing", sniffing)

        root.put("inbounds", JSONArray().put(socksInbound).put(tunInbound))

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
                                .put("method", sanitizeSsMethod(p.encryption))
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
        // mandatory when VoIP media must traverse a tunnel. BUT it must NOT be
        // forced for WebSocket: WS transport is TCP-only, and Cloudflare-fronted
        // VLESS+WS servers (the common case) almost always run with mux disabled.
        // Forcing mux here makes the client frame every stream as mux/XUDP, which
        // such servers cannot decode — web pages that use short fetches survive,
        // but persistent sockets (Viber/WhatsApp messaging) stall or fail.
        val useMux = p.muxEnabled || (voipViaProxy && p.network != "ws")
        val udpViaProxy = voipViaProxy && useMux
        if (useMux) {
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
        // Internal outbound consumed by the DNS module (routing rule below
        // sends the app's port-53 packets here, the core resolves them via
        // the configured DoH servers and answers through the TUN).
        val dnsOut = JSONObject()
            .put("tag", "dns-out")
            .put("protocol", "dns")
            .put("settings", JSONObject())

        root.put("outbounds", JSONArray().put(proxy).put(direct).put(block).put(dnsOut))

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
            // Built-in DNS module: every other port-53 packet (the app's
            // system DNS lookup) goes to the internal resolver which answers
            // via DoH (TCP) through the tunnel. CRITICAL for Cloudflare-fronted
            // vless-ws servers which cannot relay raw UDP — without this the
            // lookups silently die and Viber/WhatsApp messaging fails even
            // though the tunnel itself is fine.
            rules.put(
                JSONObject().put("type", "field")
                    .put("port", 53)
                    .put("outboundTag", "dns-out")
            )
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
                "domain:tg.dev",
                "domain:mt.me"
            ))
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", telegramDomains)
                    .put("network", "udp")
                    .put("outboundTag", if (udpViaProxy) "proxy" else "direct")
            )
            // Viber — full domain list for messaging + VoIP + STUN/TURN.
            // TCP always via the tunnel; UDP follows the udpViaProxy policy:
            // on a bare WebSocket transport the server cannot relay raw UDP
            // (ports 7985/7987/5242/5243/4244 carry the messaging heartbeat),
            // so forcing UDP into the proxy silently kills those sessions.
            val viberDomains = JSONArray(listOf(
                "domain:viber.com",
                "domain:viber-cdn.net",
                "domain:almondknot.com"
            ))
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", viberDomains)
                    .put("network", "tcp")
                    .put("outboundTag", "proxy")
            )
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", viberDomains)
                    .put("network", "udp")
                    .put("outboundTag", if (udpViaProxy) "proxy" else "direct")
            )
            // Messenger / Facebook / WhatsApp domains — same TCP/UDP split.
            val messengerDomains = JSONArray(listOf(
                "domain:edge-mqtt.facebook.com",
                "domain:mqtt.facebook.com",
                "domain:facebook.com",
                "domain:fbcdn.net",
                "domain:whatsapp.com",
                "domain:whatsapp.net"
            ))
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", messengerDomains)
                    .put("network", "tcp")
                    .put("outboundTag", "proxy")
            )
            rules.put(
                JSONObject().put("type", "field")
                    .put("domain", messengerDomains)
                    .put("network", "udp")
                    .put("outboundTag", if (udpViaProxy) "proxy" else "direct")
            )
            // QUIC/HTTP3 (UDP:443): a Cloudflare-fronted vless-ws edge cannot
            // relay these packets through the WebSocket tunnel, so block them
            // and let the app fall back to HTTP/2/TCP which tunnels perfectly.
            // The domain rules above still let Viber/Messenger/WhatsApp
            // negotiate call media via XUDP before this rule runs.
            rules.put(
                JSONObject().put("type", "field")
                    .put("network", "udp")
                    .put("port", "443")
                    .put("outboundTag", "block")
            )
            // All remaining UDP — route through proxy ONLY when the tunnel can
            // actually carry it (mux/XUDP is on). For a bare WebSocket / other
            // un-muxed transport, UDP goes direct because the server cannot
            // relay it; forcing it into the proxy would just make those apps
            // hang instead of falling back.
            if (udpViaProxy) {
                rules.put(
                    JSONObject().put("type", "field")
                        .put("network", "udp")
                        .put("outboundTag", "proxy")
                )
            } else {
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

    /**
     * Mixed DNS servers: direct UDP FIRST (instant, bypasses tunnel), DoH as
     * fallback (censorship-resistant, through tunnel).
     *
     * Problem: on VPN reconnect, Viber aggressively reconnects before the new
     * tunnel is ready. DoH needs the tunnel (~200-500ms), times out, Viber gives
     * up before UDP fallback is tried. Other apps have longer retries so they
     * survive.
     *
     * Fix: UDP first → resolves in ~10ms via phone's network (Xray excluded from
     * VPN). DoH only if UDP fails (censored networks).
     *
     * Also disable DNS cache ("disableCache": true) so reconnect always gets fresh
     * resolution — stale cache from previous tunnel session can cause issues.
     */
    private fun mixedDnsServers(key: String): List<Any> {
        val udp = directIp(key)
        val doh = dohUrls(key)
        val result = mutableListOf<Any>()
        // UDP first: instant, bypasses tunnel (Xray process excluded from VPN)
        result.add(udp)
        // DoH fallback: censorship-resistant, through the tunnel
        result.addAll(doh)
        return result
    }

    private fun directIp(key: String): String = when (key) {
        "cloudflare" -> "1.1.1.1"
        "google" -> "8.8.8.8"
        "alidns" -> "223.5.5.5"
        "dnspod" -> "119.29.29.29"
        "opendns" -> "208.67.222.222"
        else -> "1.1.1.1"
    }

    private fun dohUrls(key: String): List<String> = when (key) {
        "cloudflare" -> listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")
        "google" -> listOf("https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query")
        "alidns" -> listOf("https://223.5.5.5/dns-query", "https://223.6.6.6/dns-query")
        "dnspod" -> listOf("https://119.29.29.29/dns-query", "https://182.254.116.116/dns-query")
        "opendns" -> listOf("https://208.67.222.222/dns-query", "https://208.67.220.220/dns-query")
        else -> listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")
    }

    private fun streamSettings(p: ServerProfile): JSONObject {
        val s = JSONObject().put("network", p.network)

        when (p.security.lowercase()) {
            "tls" -> {
                s.put("security", "tls")
                val tls = JSONObject()
                    .put("serverName", p.sni.ifBlank { p.host.ifBlank { p.address } })
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

    /**
     * Maps a subscription-declared Shadowsocks cipher to one this Xray core
     * actually supports. Modern Xray dropped the legacy non-AEAD ciphers, so
     * e.g. "chacha20-ietf" must become "chacha20-ietf-poly1305" or the core
     * fails to build the outbound ("unknown cipher method").
     */
    private fun sanitizeSsMethod(raw: String): String {
        val m = (raw ?: "").trim().lowercase()
        val supported = setOf(
            "aes-128-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305"
        )
        if (m in supported) return m
        // Legacy AliOS / old clients used this 128-bit CFB-ish stream cipher.
        return when (m) {
            "chacha20-ietf" -> "chacha20-ietf-poly1305"
            "aes-256-cfb" -> "aes-256-gcm"
            "aes-192-cfb" -> "aes-256-gcm"
            "aes-128-cfb" -> "aes-128-gcm"
            "rc4-md5" -> "chacha20-ietf-poly1305"
            else -> "aes-256-gcm"
        }
    }
}
