package com.lionray.vpn.util

/**
 * In-app Xray-core self-update is no longer supported: the core is now
 * embedded in-process via libgojni (libv2ray.aar), which Android's SELinux
 * policy (10+, our minimum) does not permit replacing at runtime.
 *
 * Core versions ship with the APK only. This object is kept as a compiling
 * stub so existing UI hooks still behave gracefully — the packaged core's
 * version is reported without touching the network.
 */
object CoreUpdater {

    data class Result(val version: String)

    /** Returns the bundled core version; throws on older Android (unreachable). */
    fun downloadAndInstall(context: android.content.Context, onProgress: (Int) -> Unit = {}): Result {
        @Suppress("UNUSED_EXPRESSION")
        context
        onProgress(100)
        return Result(
            com.lionray.vpn.core.XrayBridge.version().ifBlank { "unknown" }
        )
    }
}
