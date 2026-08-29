package com.lionray.vpn.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import go.Seq
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Bridge to Xray-core embedded IN-PROCESS via libgojni (libv2ray.aar).
 *
 * This is the same proven architecture used by v2rayNG:
 *   Android apps -> VpnService TUN fd -> Xray-core "tun" inbound (fd wired in
 *   by startLoop) -> routing rules -> VLESS/proxy outbound -> internet.
 *
 * No separate hev-socks5-tunnel process and no SOCKS hop are needed: the tun
 * inbound consumes the VpnService file descriptor handed to [startLoop].
 * A local SOCKS inbound (127.0.0.1:[SOCKS_PORT]) is still part of the config
 * so in-app IP/country checks can look at the tunnel's real exit (the app's
 * own sockets bypass the TUN via addDisallowedApplication).
 *
 * libgojni ships every ABI (arm64-v8a, armeabi-v7a, x86, x86_64), so a single
 * APK installs & runs on 32-bit and 64-bit phones alike.
 */
object XrayBridge {

    private const val TAG = "LionRay/Xray"

    /**
     * Displayed Xray-core version for the About panel.
     *
     * WARNING: this is DISPLAY-ONLY and can drift from the true core shipped in
     * the APK. Leave it BLANK so About always shows the REAL version reported by
     * the running libgojni binary (the honest value). Do NOT hardcode a number
     * here just to make it "look newer" — the 2dust AAR tags do not reliably
     * match the binary's internal Xray version, so a manual value misleads.
     * Only set it if you have verified the actual bundled core version matches.
     */
    const val BUNDLED_XRAY_VERSION = ""

    /** TUN & VpnService MTU — must match the config's tun inbound MTU. */
    const val MTU = 1500

    /** Internal TUN interface addresses (used only as the tunnel gateway). */
    const val VPN_IPV4 = "26.26.26.1"
    const val VPN_IPV6 = "fdfe:dcba:9876::1"

    /** Local auxiliary SOCKS5 inbound kept for in-app exit-IP checks. */
    const val SOCKS_PORT = 10808

    interface StatusListener {
        /** level -1 => core stopped itself. */
        fun onStatus(level: Int, msg: String)
    }

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var envReady = false
    private var controller: CoreController? = null

    /** Counts traffic the router sent to the "block" outbound (ad blocker) — bytes/session. */
    private val blocked = AtomicLong(0)

    /** Previous cumulative totals, so stats() can return per-interval deltas. */
    private val lastDown = AtomicLong(0)
    private val lastUp = AtomicLong(0)

    fun blockedCount(): Long = blocked.get()
    fun resetBlocked() = blocked.set(0)

    @Volatile
    var listener: StatusListener? = null

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Lightweight: only stores the context. Heavy Go/lib init happens lazily on first start(). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ensureEnv(): Boolean {
        if (envReady) return true
        synchronized(lock) {
            if (envReady) return true
            val ctx = appContext ?: return false
            return try {
                val assetDir = File(ctx.filesDir, "xray-assets").apply { mkdirs() }
                copyAssets(ctx, assetDir)
                // XUDP base key must decode to exactly 32 bytes. Xray base64-
                // decodes the env value (xray.xudp.basekey) and rejects anything
                // that isn't 32 bytes. v2rayNG's exact recipe: zero-pad the
                // Android ID's UTF-8 bytes to 32, then URL-safe unpadded base64.
                val androidId = runCatching {
                    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull()?.ifEmpty { null } ?: "lionrayvpn"
                val deviceId = android.util.Base64.encodeToString(
                    androidId.toByteArray(Charsets.UTF_8).copyOf(32),
                    android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
                )
                Seq.setContext(ctx)
                Libv2ray.initCoreEnv(assetDir.absolutePath, deviceId)
                envReady = true
                true
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Log.e(TAG, "env init failed", t)
                false
            }
        }
    }

    /** Asset dir holds the geoip/geosite data the core may need at runtime. */
    private fun copyAssets(ctx: Context, dir: File) {
        listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat").forEach { name ->
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 0) return@forEach
            try {
                ctx.assets.open(name).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "asset copy skipped: $name", t)
            }
        }
    }

    fun version(): String {
        // Manual display version wins — the operator keeps it in sync with the
        // bundled aar so About always shows the correct core version.
        if (BUNDLED_XRAY_VERSION.isNotBlank()) return BUNDLED_XRAY_VERSION
        if (!ensureEnv()) return "unknown"
        return try {
            Libv2ray.checkVersionX()
        } catch (t: Throwable) {
            Log.e(TAG, "version check failed", t)
            "unknown"
        }
    }

    /**
     * Starts the in-process Xray core with [configJson] and wires the
     * VpnService [tunFd] into the config's "tun" inbound.
     */
    fun start(configJson: String, tunFd: Int): Boolean {
        synchronized(lock) {
            lastError = ""
            if (isRunning) return true
            if (!ensureEnv()) { dumpError("env init failed:\n$lastError"); return false }
            return try {
                val handler = object : CoreCallbackHandler {
                    // The core emits its REAL failure reason here (level < 0 means
                    // it stopped itself). Capture it so the UI can show the exact
                    // cause instead of a generic "core failed to start".
                    override fun onEmitStatus(level: Long, msg: String?): Long {
                        if (!msg.isNullOrBlank()) {
                            if (level < 0) lastError = msg
                            else if (lastError.isBlank()) lastError = msg
                        }
                        return 0
                    }
                    override fun startup(): Long = 0
                    override fun shutdown(): Long = 0
                }
                val c = Libv2ray.newCoreController(handler)
                c.startLoop(configJson, tunFd)
                controller = c
                isRunning = c.isRunning
                blocked.set(0)
                if (!isRunning && lastError.isBlank()) lastError = "core failed to start"
                if (!isRunning) dumpError("startLoop returned not-running:\n$lastError")
                isRunning
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Log.e(TAG, "start failed", t)
                dumpError("start threw:\n$lastError")
                controller = null
                isRunning = false
                false
            }
        }
    }

    /** Persist the last error to external files so it can be pulled for diagnosis. */
    private fun dumpError(msg: String) {
        runCatching {
            val dir = appContext?.getExternalFilesDir(null) ?: return
            val abi = android.os.Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"
            val header = buildString {
                append("time: ")
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                append("\nmodel: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
                append("\nandroid: ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")")
                append("\nabi: ").append(abi)
                append("\ncore: ").append(BUNDLED_XRAY_VERSION.ifBlank { "unknown" })
            }
            java.io.File(dir, "xray_error.log").writeText("$header\n\n$msg\n")
        }
    }

    fun stop() {
        synchronized(lock) {
            val c = controller ?: return
            controller = null
            isRunning = false
            runCatching { c.stopLoop() }
        }
    }

    /**
     * Bytes transferred since the previous query, as [down, up].
     * This libv2ray build exposes [CoreController.queryStats] (a cumulative
     * per-outbound counter) instead of queryAllOutboundTrafficStats, so we
     * sum the proxy/direct/block outbounds and return the per-interval delta.
     */
    fun stats(): LongArray? {
        val c = controller ?: return null
        if (!c.isRunning) return null
        return try {
            val dProxy = c.queryStats("proxy", 0)
            val uProxy = c.queryStats("proxy", 1)
            val dDirect = c.queryStats("direct", 0)
            val uDirect = c.queryStats("direct", 1)
            val dBlock = c.queryStats("block", 0)
            val uBlock = c.queryStats("block", 1)
            val down = dProxy + dDirect + dBlock
            val up = uProxy + uDirect + uBlock
            val dDown = (down - lastDown.get()).also { lastDown.set(down) }
            val dUp = (up - lastUp.get()).also { lastUp.set(up) }
            blocked.set(dBlock)
            longArrayOf(dDown.coerceAtLeast(0), dUp.coerceAtLeast(0))
        } catch (t: Throwable) {
            Log.e(TAG, "stats failed", t)
            null
        }
    }
}
