package com.lionray.vpn.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Bridge to an EXTERNAL Xray-core executable process.
 *
 * Binary resolution order:
 *   1. nativeLibraryDir/libxray.so – core bundled in jniLibs (extracted on
 *        install; exec is permitted there on every Android version).
 *   2. filesDir/xray/xray          – core downloaded via "Update Core".
 *
 * Flow:
 *   hev-socks5-tunnel (in-process, TUN fd) -> SOCKS 127.0.0.1:<port> -> xray process
 */
object XrayBridge {

    private const val TAG = "LionRay/Xray"

    interface StatusListener {
        /** level -1 => core stopped itself */
        fun onStatus(level: Int, msg: String)
    }

    private val lock = Any()
    private var process: Process? = null
    @Volatile private var appContext: Context? = null

    // counts connections the router sent to the "block" outbound (ad blocker)
    private val blocked = java.util.concurrent.atomic.AtomicLong(0)

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

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching { coreWorkDir().mkdirs() }

        // Purge stale filesDir copy on Android 10+ (exec blocked by SELinux)
        if (Build.VERSION.SDK_INT >= 29) {
            val dl = File(appContext!!.filesDir, "xray/xray")
            if (dl.exists()) runCatching { dl.delete() }
        }

        val prefs = appContext!!.getSharedPreferences("xray_bridge", 0)
        val savedCode = prefs.getInt("version_code", 0)
        val currentCode = try {
            appContext!!.packageManager.getPackageInfo(appContext!!.packageName, 0)
                .let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode }
        } catch (_: Throwable) { 0 }
        if (currentCode > 0 && currentCode != savedCode) {
            prefs.edit().putInt("version_code", currentCode).commit()
        }
    }

    fun coreWorkDir(): File =
        File(appContext!!.filesDir, "xray")

    /** On Android 10+ the updated core is written to nativeLibraryDir/libxray.so
     *  (only executable path). On older devices filesDir/xray/xray is used. */
    fun activeBinary(): File {
        val dl = File(appContext!!.filesDir, "xray/xray")
        if (dl.exists() && dl.canExecute() && dl.length() > 10_000_000L) return dl
        val bundled = File(appContext!!.applicationInfo.nativeLibraryDir, "libxray.so")
        if (bundled.exists() && bundled.length() > 10_000_000L) return bundled
        return bundled
    }

    /** true when a user-downloaded core is present (filesDir or nativeLibraryDir). */
    fun hasUpdatedCore(): Boolean {
        val dl = File(appContext!!.filesDir, "xray/xray")
        return dl.exists() && dl.length() > 10_000_000L
    }

    private val VER_RE = Regex("""(\d+\.\d+\.\d+)""")

    fun version(): String = try {
        val bin = activeBinary()
        if (!bin.exists()) "missing"
        else ProcessBuilder(bin.absolutePath, "version")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { VER_RE.find(it)?.groupValues?.get(1) }
                    .firstOrNull()
                    ?: "unknown"
            }
    } catch (t: Throwable) {
        Log.e(TAG, "version check failed", t)
        "unknown"
    }

    /**
     * Starts Xray-core as a detached process with [configJson].
     * [tunFd] is unused here (the in-process hev tunnel owns the fd).
     */
    fun start(configJson: String, tunFd: Int): Boolean {
        synchronized(lock) {
            lastError = ""
            if (isRunning) return true
            val ctx = appContext ?: return false
            val bin = activeBinary()
            if (!bin.exists()) {
                lastError = "Xray binary not found at ${bin.absolutePath}"
                return false
            }
            return try {
                val work = coreWorkDir().apply { mkdirs() }
                val cfgFile = File(work, "config.json")
                cfgFile.writeText(configJson)
                runCatching { bin.setExecutable(true, false) }

                val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfgFile.absolutePath)
                    .directory(work)
                    .redirectErrorStream(true)
                pb.environment()["XRAY_LOCATION_ASSET"] = work.absolutePath
                val proc = pb.start()
                process = proc

                Thread {
                    try {
                        proc.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                Log.i(TAG, "core: $line")
                                // xray info log: "accepted tcp:host:443 [socks-in -> block]"
                                if (line.contains("-> block") || line.contains("[block]")) {
                                    blocked.incrementAndGet()
                                }
                                if (line.contains("Failed to start", true) ||
                                    line.contains("core: failed", true)
                                ) listener?.onStatus(2, line.take(300))
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    if (proc.waitFor() != 0 && isRunning) {
                        isRunning = false
                        listener?.onStatus(-1, "core exited")
                    }
                }.apply { isDaemon = true }.start()

                // give the core a moment; a config error kills it instantly
                Thread.sleep(600)
                if (proc.isAlive) {
                    isRunning = true
                    true
                } else {
                    lastError = "Xray exited immediately (bad config?)"
                    process = null
                    false
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Log.e(TAG, "start failed", t)
                process = null
                false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val proc = process ?: return
            process = null
            runCatching { proc.destroy() }
            Thread {
                try {
                    if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Throwable) {
                }
            }.start()
            isRunning = false
        }
    }
}
