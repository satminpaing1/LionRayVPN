package com.lionray.vpn.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lionray.vpn.ui.MainActivity
import com.lionray.vpn.core.VpnBus
import com.lionray.vpn.core.VpnState

/**
 * Notification-shade quick tile: pull down and tap to connect/disconnect,
 * exactly like the big-name VPN apps.
 */
class LionRayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        sync()
    }

    override fun onClick() {
        super.onClick()
        when (VpnBus.state.value) {
            VpnState.CONNECTED ->
                startService(
                    Intent(this, LionRayVpnService::class.java)
                        .setAction(LionRayVpnService.ACTION_STOP)
                )

            else -> {
                // VPN permission already granted? start right away;
                // otherwise open the app so the user grants it once.
                if (VpnService.prepare(this) == null) {
                    startForegroundServiceCompat()
                    qsTile?.state = Tile.STATE_ACTIVE
                    qsTile?.updateTile()
                } else {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startForegroundServiceCompat() {
        val i = Intent(this, LionRayVpnService::class.java)
            .setAction(LionRayVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun sync() {
        val tile = qsTile ?: return
        tile.state = when (VpnBus.state.value) {
            VpnState.CONNECTED -> Tile.STATE_ACTIVE
            VpnState.CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
