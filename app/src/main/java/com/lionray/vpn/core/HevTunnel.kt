package com.lionray.vpn.core

import android.content.Context
import android.os.ParcelFileDescriptor
import com.v2ray.ang.service.TProxyService
import java.io.File

/**
 * tun2socks layer:
 *   VpnService TUN fd -> hev-socks5-tunnel -> local Xray SOCKS5 inbound.
 *
 * This is the same proven architecture used by v2rayNG / Orbot, and unlike
 * Xray-core's experimental "tun" inbound it works on every Android version
 * without netlink permissions.
 */
object HevTunnel {

    const val MTU = 8500
    const val VPN_IPV4 = "26.26.26.1"
    const val VPN_IPV6 = "fdfe:dcba:9876::1"
    const val SOCKS_PORT = 10808

    @Volatile
    var lastError: String = ""
        private set

    fun start(context: Context, pfd: ParcelFileDescriptor): Boolean {
        lastError = ""
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $MTU")
            appendLine("  ipv4: $VPN_IPV4")
            appendLine("  ipv6: '$VPN_IPV6'")
            appendLine("socks5:")
            appendLine("  port: $SOCKS_PORT")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            // generous UDP NAT lifetime — long calls with quiet stretches
            // must not have their media mapping reaped mid-conversation
            appendLine("  udp-read-write-timeout: 600000")
            appendLine("  log-level: warn")
        }
        return try {
            val file = File(context.filesDir, "hev-tunnel.yaml")
            file.writeText(yaml)
            TProxyService.start(file.absolutePath, pfd.fd)
        } catch (t: Throwable) {
            lastError = t.toString()
            false
        }
    }

    fun stop() {
        runCatching { TProxyService.stop() }
    }

    /** [txPackets, txBytes, rxPackets, rxBytes] or null. tx=upload, rx=download. */
    fun stats(): LongArray? = TProxyService.stats()
}
