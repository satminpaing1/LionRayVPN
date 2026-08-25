package com.lionray.vpn.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lionray.vpn.LionRayApp
import com.lionray.vpn.R
import com.lionray.vpn.core.HevTunnel
import com.lionray.vpn.core.Speed
import com.lionray.vpn.core.VpnBus
import com.lionray.vpn.core.VpnState
import com.lionray.vpn.core.XrayBridge
import com.lionray.vpn.core.XrayConfigBuilder
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.ui.MainActivity
import com.lionray.vpn.util.PingEngine
import com.lionray.vpn.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System-wide VPN:
 *
 *  Android apps -> TUN (VpnService) -> Xray-core "tun" inbound (fd passed in)
 *      -> routing rules -> VLESS proxy outbound -> internet
 *
 * Our own package is disallowed from the TUN, so Xray's outbound sockets use
 * the physical network directly - no traffic loop.
 */
class LionRayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.lionray.vpn.action.START"
        const val ACTION_STOP = "com.lionray.vpn.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "LionRay/Vpn"
        private const val VPN_MTU = HevTunnel.MTU
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stopping = AtomicBoolean(false)
    private var tunInterface: ParcelFileDescriptor? = null

    // ---- auto-reconnect (WiFi <-> SIM) ------------------------------------
    @Volatile private var lastConfig: String? = null
    @Volatile private var netWatchArmed = false
    private var reconnectAttempts = 0
    private var pendingReconnect: Runnable? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // ---- auto-failover (dead server -> fastest working alternative) --------
    private var watchdogJob: Job? = null
    private val failoverRunning = AtomicBoolean(false)
    private var failoverAttempts = 0

    private fun armNetworkWatcher() {
        if (netWatchArmed) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scheduleCoreRestart()
            }

            override fun onLost(network: android.net.Network) {
                // the replacement network fires onAvailable right after;
                // scheduling here too keeps the gap short when it doesn't
                scheduleCoreRestart()
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
            networkCallback = cb
            netWatchArmed = true
        }
    }

    private fun disarmNetworkWatcher() {
        if (!netWatchArmed) return
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback!!)
        }
        networkCallback = null
        netWatchArmed = false
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
    }

    /** Debounced: rapid WiFi<->SIM flaps collapse into one restart. */
    private fun scheduleCoreRestart(delayMs: Long = 2500L) {
        if (stopping.get()) return
        if (!SettingsStore.autoReconnect(applicationContext)) return
        if (lastConfig == null) return
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { restartCoreOnly(0) }
        pendingReconnect = r
        mainHandler.postDelayed(r, delayMs)
    }

    /**
     * Re-launches ONLY the Xray process with the same config — the TUN and
     * hev tunnel survive the underlying network switch, so new outbound
     * sockets simply bind to the fresh network.
     */
    private fun restartCoreOnly(attempt: Int) {
        val cfg = lastConfig ?: return
        if (stopping.get()) return
        if (VpnBus.state.value != VpnState.CONNECTED) return
        scope.launch(Dispatchers.IO) {
            runCatching { XrayBridge.stop() }
            delay(400)
            val ok = XrayBridge.start(cfg, 0)
            withContext(Dispatchers.Main) {
                if (ok && !stopping.get()) {
                    reconnectAttempts = 0
                    VpnBus.statusMessage.value =
                        ProfileStore.activeProfile()?.displayName().orEmpty() +
                            "  •  " + getString(R.string.reconnected_ok)
                } else if (attempt < 6 && !stopping.get()) {
                    // network may not be fully usable yet — back off and retry
                    pendingReconnect?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable { restartCoreOnly(attempt + 1) }
                    pendingReconnect = r
                    mainHandler.postDelayed(r, 3000L * (attempt + 1))
                } else {
                    gracefulStop()
                }
            }
        }
    }

    // ---------------------------------------------------------- auto-failover

    /** Core died / server unreachable: failover to another key, else stop. */
    private fun attemptRecoveryOrStop() {
        if (stopping.get()) return
        if (VpnBus.state.value != VpnState.CONNECTED) {
            gracefulStop()
            return
        }
        if (!SettingsStore.autoFailover(applicationContext)) {
            gracefulStop()
            return
        }
        launchFailover()
    }

    /**
     * Probes every alternative profile concurrently, activates the fastest
     * reachable one and re-establishes the tunnel. Rescans up to 3 rounds
     * (5s apart) when nothing is reachable; gives up entirely after 3
     * successful switches in one session.
     */
    private fun launchFailover() {
        if (!failoverRunning.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                repeat(3) {
                    if (stopping.get()) return@launch
                    val currentId = ProfileStore.activeProfile()?.id
                    val candidates = ProfileStore.profiles.value.filter {
                        it.id != currentId && it.address.isNotBlank()
                    }
                    if (candidates.isEmpty()) {
                        withContext(Dispatchers.Main) { gracefulStop() }
                        return@launch
                    }

                    // probe all alternatives at once (fresh results only)
                    PingEngine.results.value = emptyMap()
                    PingEngine.pingAll(candidates)
                    val deadline = System.currentTimeMillis() + 25_000
                    while (System.currentTimeMillis() < deadline &&
                        !stopping.get() &&
                        !candidates.all { PingEngine.results.value.containsKey(it.id) }
                    ) delay(300)

                    val best = PingEngine.results.value.entries
                        .filter { it.key != currentId && it.value > 0 }
                        .minByOrNull { it.value }
                        ?.let { hit -> candidates.firstOrNull { it.id == hit.key } }

                    if (best != null) {
                        if (stopping.get()) return@launch
                        failoverAttempts++
                        if (failoverAttempts > 3) {
                            withContext(Dispatchers.Main) { gracefulStop() }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            ProfileStore.setActive(best.id)
                            VpnBus.statusMessage.value =
                                getString(R.string.failover_switched, best.displayName())
                            updateNotification(
                                getString(R.string.failover_switched, best.displayName())
                            )
                            startTunnel()
                        }
                        return@launch
                    }
                    delay(5_000) // nothing reachable — wait, then rescan
                }
                withContext(Dispatchers.Main) { gracefulStop() }
            } finally {
                failoverRunning.set(false)
            }
        }
    }

    /**
     * Health watchdog for the "xray alive but upstream blocked" case.
     * Traffic-aware: if bytes are flowing through the tunnel the server is
     * obviously alive, so we skip probing entirely and only TCP-probe when
     * the session is quiet. 3 consecutive failed probes (~90s) trigger a
     * failover even though the core process never exited.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            var fails = 0
            var lastTotal = -1L
            while (VpnBus.state.value == VpnState.CONNECTED && !stopping.get()) {
                delay(30_000)
                if (VpnBus.state.value != VpnState.CONNECTED || stopping.get()) break
                val p = ProfileStore.activeProfile() ?: break

                // active traffic proves liveness — reset & skip the probe
                val s = HevTunnel.stats()
                val total = s?.let { it[1] + it[3] } ?: -1L
                if (total > 0 && lastTotal > 0 && total - lastTotal > 65536) {
                    fails = 0
                    lastTotal = total
                    continue
                }
                if (total >= 0) lastTotal = maxOf(lastTotal, total)

                val ms = runCatching {
                    PingEngine.tcpPing(p.address, p.port, 5000)
                }.getOrDefault(-1)
                fails = if (ms >= 0) 0 else fails + 1
                if (fails >= 3) {
                    withContext(Dispatchers.Main) { attemptRecoveryOrStop() }
                    break
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        XrayBridge.listener = object : XrayBridge.StatusListener {
            override fun onStatus(level: Int, msg: String) {
                if (level == -1 && !stopping.get()) {
                    // Core died on its own -> try another server, else stop
                    mainHandler.post { attemptRecoveryOrStop() }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                gracefulStop()
                return START_NOT_STICKY
            }
            else -> {
                failoverAttempts = 0
                goForeground(getString(R.string.notif_connecting))
                scope.launch { startTunnel() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        // User revoked VPN permission from system settings
        gracefulStop()
    }

    override fun onDestroy() {
        disarmNetworkWatcher()
        runCatching { XrayBridge.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ core

    private suspend fun startTunnel() {
        if (VpnBus.state.value != VpnState.DISCONNECTED) teardownCore()
        stopping.set(false)

        VpnBus.statusMessage.value = ""
        VpnBus.state.value = VpnState.CONNECTING

        val profile = ProfileStore.activeProfile()
        if (profile == null || profile.address.isBlank() || profile.uuid.isBlank()) {
            fail(getString(R.string.err_no_server))
            return
        }

        val pfd = establishVpn()
        if (pfd == null) {
            fail(getString(R.string.err_vpn_establish))
            return
        }
        tunInterface = pfd

        val config = run {
            val mode = SettingsStore.routingMode(applicationContext)
            val dns = SettingsStore.currentDns(applicationContext)
            if (mode == SettingsStore.MODE_SPLIT_CN && XrayConfigBuilder.cnDomains.isEmpty()) {
                XrayConfigBuilder.cnDomains = loadCnDomains()
            }
            XrayConfigBuilder.adBlock = SettingsStore.adBlock(applicationContext)
            if (XrayConfigBuilder.adBlock && XrayConfigBuilder.adDomains.isEmpty()) {
                XrayConfigBuilder.adDomains = loadListFromAssets("ad_domains.txt")
            }
            XrayConfigBuilder.bypassDomains = SettingsStore.bypassDomains(applicationContext)
            XrayConfigBuilder.voipViaProxy = SettingsStore.voipViaVpn(applicationContext)
            XrayConfigBuilder.build(
                profile,
                HevTunnel.SOCKS_PORT,
                mode,
                if (dns.domestic) dns.servers else emptyList()
            )
        }
        // Keep a copy for debugging (Android/data/com.lionray.vpn/files/)
        runCatching {
            val dir = getExternalFilesDir(null)
            java.io.File(dir, "last_config.json").writeText(config)
        }
        lastConfig = config
        armNetworkWatcher()
        val ok = withContext(Dispatchers.IO) {
            XrayBridge.start(config, 0)
        }
        if (!ok) {
            fail(getString(R.string.err_start_failed) + "\n" + XrayBridge.lastError)
            return
        }

        // Route the TUN into the local Xray SOCKS inbound
        val hevOk = withContext(Dispatchers.IO) {
            HevTunnel.start(applicationContext, pfd)
        }
        if (!hevOk) {
            fail(getString(R.string.err_tun2socks) + "\n" + HevTunnel.lastError)
            return
        }

        VpnBus.startedAtMs = System.currentTimeMillis()
        VpnBus.statusMessage.value =
            profile.displayName() + "  •  " + XrayBridge.version()
        VpnBus.state.value = VpnState.CONNECTED
        updateNotification(getString(R.string.notif_connected, profile.displayName()))
        startSpeedPolling()
        startWatchdog()
    }

    private var speedJob: kotlinx.coroutines.Job? = null

    /**
     * Samples the hev tunnel counters every second and publishes the
     * throughput to [VpnBus.speed]. In the stats array tx = upload
     * (device -> internet) and rx = download (internet -> device).
     */
    private fun startSpeedPolling() {
        speedJob?.cancel()
        XrayBridge.resetBlocked()
        VpnBus.blockedCount.value = 0L
        speedJob = scope.launch {
            var lastTx = -1L
            var lastRx = -1L
            var lastMs = 0L
            while (VpnBus.state.value == VpnState.CONNECTED) {
                val s = HevTunnel.stats()
                if (s != null && s.size >= 4) {
                    val now = System.currentTimeMillis()
                    if (lastTx >= 0 && now > lastMs) {
                        val dt = (now - lastMs).coerceAtLeast(1)
                        val down = ((s[3] - lastRx).coerceAtLeast(0)) * 1000 / dt
                        val up = ((s[1] - lastTx).coerceAtLeast(0)) * 1000 / dt
                        VpnBus.speed.value = Speed(down, up)
                    }
                    lastTx = s[1]
                    lastRx = s[3]
                    lastMs = now
                    // cumulative session totals for the Home data-usage row
                    VpnBus.usage.value =
                        com.lionray.vpn.core.Usage(downBytes = s[3], upBytes = s[1])
                }
                VpnBus.blockedCount.value = XrayBridge.blockedCount()
                delay(1000)
            }
        }
    }

    private fun stopSpeedPolling() {
        speedJob?.cancel()
        speedJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        VpnBus.speed.value = Speed(0, 0)
    }

    /**
     * Builds the TUN interface that captures the WHOLE device traffic.
     *  - routes 0.0.0.0/0 + ::/0  => every IPv4/IPv6 packet enters our tunnel
     *  - DNS servers come from the user's selected preset; domestic presets
     *    are also routed direct in the core (see XrayConfigBuilder)
     *  - our own app is excluded so xray's outbound sockets bypass the TUN
     */
    private fun establishVpn(): ParcelFileDescriptor? {
        val dns = SettingsStore.currentDns(this)
        val builder = Builder()
            .setSession("LionRay VPN")
            .setMtu(VPN_MTU)
            .addAddress("26.26.26.1", 24)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
        for (s in dns.servers) builder.addDnsServer(s)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        // user-picked apps that must not go through the VPN
        for (pkg in SettingsStore.bypassApps(this)) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(false)
        }
        return builder.establish()
    }

    private fun loadCnDomains(): List<String> =
        loadListFromAssets("cn_domains.txt")

    private fun loadListFromAssets(name: String): List<String> = try {
        assets.open(name).bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            // entries get "domain:" prefix so xray matches subdomains too
            .map { if (it.startsWith("domain:") || it.startsWith("full:")) it else "domain:$it" }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun fail(message: String) {
        Log.e(TAG, "start failed: $message")
        stopSpeedPolling()
        VpnBus.statusMessage.value = message
        VpnBus.state.value = VpnState.ERROR
        runCatching {
            val dir = getExternalFilesDir(null)
            java.io.File(dir, "last_error.txt").writeText(
                "time: ${System.currentTimeMillis()}\nprofile: ${ProfileStore.activeProfile()?.toShareUri().orEmpty()}\nerror:\n$message\n"
            )
        }
        teardownCore()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun gracefulStop() {
        if (!stopping.compareAndSet(false, true)) return
        lastConfig = null
        failoverAttempts = 0
        disarmNetworkWatcher()
        stopSpeedPolling()
        VpnBus.startedAtMs = 0L
        VpnBus.statusMessage.value = ""
        VpnBus.state.value = VpnState.DISCONNECTED
        Thread { teardownCore() }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownCore() {
        HevTunnel.stop()
        runCatching { XrayBridge.stop() }
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    // ----------------------------------------------------------- notification

    private fun goForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, LionRayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LionRayApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_stop, getString(R.string.btn_disconnect), disconnect)
            .build()
    }
}
