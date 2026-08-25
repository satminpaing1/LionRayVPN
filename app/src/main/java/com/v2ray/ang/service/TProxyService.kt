package com.v2ray.ang.service

/**
 * JNI bridge to libhev-socks5-tunnel.so
 * (hev-socks5-tunnel, MIT license, https://github.com/heiher/hev-socks5-tunnel)
 *
 * The prebuilt native libraries register their methods via RegisterNatives
 * inside JNI_OnLoad with these EXACT signatures (from the pinned hev-jni.c):
 *
 *   TProxyStartService  (Ljava/lang/String;I)V   <- VOID, not boolean!
 *   TProxyStopService   ()V                      <- VOID, not boolean!
 *   TProxyGetStats      ()[J
 *
 * If any name/signature here deviates, RegisterNatives fails, JNI_OnLoad
 * returns JNI_ERR and System.loadLibrary throws - the engine can never start.
 */
object TProxyService {

    @Volatile
    var lastError: String = ""
        private set

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    private external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    private external fun TProxyStopService()

    @JvmStatic
    private external fun TProxyGetStats(): LongArray?

    /** Spawns the engine thread; returns false only if loading/spawning threw. */
    fun start(configPath: String, fd: Int): Boolean = try {
        lastError = ""
        TProxyStartService(configPath, fd)
        true
    } catch (t: Throwable) {
        lastError = t.toString()
        false
    }

    fun stop(): Boolean = try {
        TProxyStopService()
        true
    } catch (t: Throwable) {
        lastError = t.toString()
        false
    }

    /** [txPackets, txBytes, rxPackets, rxBytes] or null. tx=upload, rx=download. */
    fun stats(): LongArray? = try {
        TProxyGetStats()
    } catch (t: Throwable) {
        null
    }
}
