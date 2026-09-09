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

    /** User-defined domains that go DIRECT (phone IP) instead of proxy. */
    @Volatile
    var bypassDomains: List<String> = emptyList()

    fun build(
        p: ServerProfile,
        socksPort: Int = XrayBridge.SOCKS_PORT,
        routingMode: String = SettingsStore.MODE_GLOBAL,
        dns: SettingsStore.Dns = SettingsStore.dnsPresets().first(),
        logFileDir: String? = null
    ): String {
        val root = JSONObject()
        val log = JSONObject().put("loglevel", "warning")
        // When a writable directory is supplied the core writes a full access
        // log (every connection: source -> dest -> routing decision) and an
        // error log to disk. onEmitStatus in AndroidLibXrayLite only surfaces
        // lifecycle events, so this is the ONLY way to see Viber's actual
        // connection attempts and which rule they matched.
        if (logFileDir != null) {
            log.put("access", java.io.File(logFileDir, "xray-access.log").absolutePath)
            log.put("error", java.io.File(logFileDir, "xray-error.log").absolutePath)
        }
        root.put("log", log)

        // FakeDNS: gives the app a fake 198.18.x.x IP per query, then maps it
        // back to the real domain when the connection arrives. This lets the
        // tunnel route by domain (fast, no second lookup) and keeps DNS quick.
        root.put(
            "fakedns",
            JSONArray()
                .put(JSONObject().put("ipPool", "198.18.0.0/15").put("poolSize", 65535))
        )

        // Built-in DNS module: FakeDNS first, DoH via the tunnel as the real
        // resolver, "localhost" as the final system fallback. Port-53 packets
        // are intercepted by the dns-out rule below.
        root.put(
            "dns",
            JSONObject()
                .put("queryStrategy", "UseIP")
                .put("servers", JSONArray(listOf("fakedns") + dohUrls(dns.key) + "localhost"))
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
            .put("destOverride", JSONArray(listOf("http", "tls", "fakedns")))
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
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "xray0")
                    .put("MTU", XrayBridge.MTU)
                    .put("userLevel", 8)
                    .put("autoRoute", true)
                    .put("strictRoute", true)
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
        val useMux = p.muxEnabled
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
        // Outbounds: proxy (the tunnel), direct, block, and dns-out (internal
        // resolver consumed by the port-53 routing rule below).
        val dnsOut = JSONObject()
            .put("tag", "dns-out")
            .put("protocol", "dns")
            .put("settings", JSONObject())

        root.put("outbounds", JSONArray().put(proxy).put(direct).put(block).put(dnsOut))

        // ---------------- routing ----------------
        val rules = JSONArray()

        // App DNS queries go to the built-in resolver (required for FakeDNS).
        rules.put(
            JSONObject().put("type", "field")
                .put("port", 53)
                .put("outboundTag", "dns-out")
        )
        // Private/loopback ranges stay on the device — geoip:private covers all
        // RFC1918 + IPv6 ULA ranges (v2box reference config).
        rules.put(
            JSONObject().put("type", "field")
                .put("outboundTag", "direct")
                .put("ip", JSONArray(listOf("geoip:private")))
        )
        // Everything else routes through the tunnel.
        rules.put(
            JSONObject().put("type", "field")
                .put("port", "0-65535")
                .put("network", "tcp,udp")
                .put("outboundTag", "proxy")
        )

        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "IPIfNonMatch")
                .put("rules", rules)
        )

        return root.toString(2)
    }

    /**
     * DoH server URLs per DNS preset — the first is primary, the rest are
     * fallbacks. "localhost" is appended by build() so the core can fall back
     * to the Android system resolver if both DoH endpoints are unreachable.
     */
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
                // Plain TLS client; no inline handshake fragmentation.
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
