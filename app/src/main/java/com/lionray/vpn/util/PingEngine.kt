package com.lionray.vpn.util

import android.os.SystemClock
import com.lionray.vpn.data.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP handshake latency tester ("ping").
 * results: profileId -> milliseconds (-1 = timeout / unreachable)
 */
object PingEngine {

    const val TIMEOUT_MS = -1

    /** Never open more than this many probe sockets at once, so pinging a
     *  huge subscription does not saturate the network. */
    private val gate = Semaphore(6)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<Long, Job>()
    private var autoJob: Job? = null

    val results = MutableStateFlow<Map<Long, Int>>(emptyMap())

    fun ping(profile: ServerProfile) {
        if (profile.address.isBlank()) return
        if (inflight.containsKey(profile.id)) return
        inflight[profile.id] = scope.launch {
            val ms = gate.withPermit { tcpPing(profile.address, profile.port) }
            emit(profile.id, ms)
            inflight.remove(profile.id)
        }
    }

    fun pingAll(list: List<ServerProfile>) {
        list.forEach { ping(it) }
    }

    fun setAuto(enabled: Boolean, intervalMs: Long = 60_000, provider: () -> List<ServerProfile>) {
        autoJob?.cancel()
        autoJob = null
        if (enabled) {
            autoJob = scope.launch {
                while (isActive) {
                    pingAll(provider())
                    delay(intervalMs)
                }
            }
        }
    }

    private fun emit(id: Long, ms: Int) {
        results.value = results.value + (id to ms)
    }

    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = 4000): Int =
        withContext(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            runCatching {
                Socket().apply {
                    soTimeout = timeoutMs
                    connect(InetSocketAddress(host, port), timeoutMs)
                    close()
                }
            }.map {
                (SystemClock.elapsedRealtime() - start).toInt()
            }.getOrDefault(TIMEOUT_MS)
        }
}
