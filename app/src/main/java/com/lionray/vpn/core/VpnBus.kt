package com.lionray.vpn.core

import kotlinx.coroutines.flow.MutableStateFlow

enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Live throughput in bytes per second. */
data class Speed(val downBps: Long = 0L, val upBps: Long = 0L)

/** Cumulative bytes transferred during the current session. */
data class Usage(val downBytes: Long = 0L, val upBytes: Long = 0L)

/** Global connection state shared between the VPN service and activities. */
object VpnBus {
    val state = MutableStateFlow(VpnState.DISCONNECTED)
    val statusMessage = MutableStateFlow("")
    val speed = MutableStateFlow(Speed())
    val usage = MutableStateFlow(Usage())
    val blockedCount = MutableStateFlow(0L)

    @Volatile
    var startedAtMs: Long = 0L
}
