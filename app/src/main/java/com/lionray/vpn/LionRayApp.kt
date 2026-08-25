package com.lionray.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lionray.vpn.core.XrayBridge
import com.lionray.vpn.data.ProfileStore
import com.lionray.vpn.data.SubStore
import com.lionray.vpn.util.ApkUpdater
import com.lionray.vpn.util.SettingsStore
import com.lionray.vpn.util.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LionRayApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ProfileStore.init(this)
        SubStore.init(this)
        XrayBridge.init(this)
        createNotificationChannels()
        checkCoreUpdate()
    }

    /** Background check: is there a newer Xray-core release on GitHub? */
    private fun checkCoreUpdate() {
        appScope.launch {
            if (Build.VERSION.SDK_INT >= 29) {
                checkApkUpdate()
            } else {
                val installed = XrayBridge.version()
                val latest = runCatching { VersionChecker.latestXray() }.getOrNull()
                if (installed != "unknown" && latest != null &&
                    VersionChecker.isNewer(installed, latest)
                ) {
                    SettingsStore.setCoreUpdateAvailable(this@LionRayApp, true)
                    SettingsStore.setCoreUpdateLatestVersion(this@LionRayApp, latest)
                } else {
                    SettingsStore.clearCoreUpdateState(this@LionRayApp)
                }
            }
            mainHandler.post { coreUpdateChecked = true }
        }
    }

    private suspend fun checkApkUpdate() {
        val apkInfo = runCatching { ApkUpdater.fetchLatestApk() }.getOrNull()
        if (apkInfo != null && ApkUpdater.isNewer(apkInfo, this@LionRayApp)) {
            SettingsStore.setApkUpdateAvailable(this@LionRayApp, true)
            SettingsStore.setApkUpdateLatestVersion(this@LionRayApp, apkInfo.versionName)
        } else {
            SettingsStore.clearApkUpdateState(this@LionRayApp)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_VPN,
                getString(R.string.channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_vpn_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_VPN = "lionray_vpn_channel"
        @Volatile var coreUpdateChecked: Boolean = false
    }
}
