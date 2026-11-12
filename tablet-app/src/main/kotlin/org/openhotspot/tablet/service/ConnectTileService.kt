package org.openhotspot.tablet.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openhotspot.tablet.MainActivity
import org.openhotspot.tablet.R
import org.openhotspot.tablet.flow.ConnectionState
import org.openhotspot.tablet.graph

/**
 * Quick Settings tile. Tapping while disconnected opens the app's connect screen (the Wi-Fi
 * confirmation prompt needs a visible app, and starting a foreground job from a tile is not
 * guaranteed to be allowed); tapping while connected disconnects without opening anything.
 */
class ConnectTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val g = graph
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch { combine(g.connection.state, g.store.phones) { st, phones -> st to phones.size }.collect { (st, n) -> render(st, n) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val g = graph
        when (g.connection.state.value) {
            is ConnectionState.Online -> g.connection.disconnect()
            is ConnectionState.Working -> g.connection.cancel()
            else -> unlockAndRun { openApp() }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated") // guarded: only runs below API 34
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: ConnectionState, phones: Int) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_hotspot)
        tile.label = getString(R.string.tile_label)
        val (tileState, sub) = when {
            phones == 0 -> Tile.STATE_INACTIVE to getString(R.string.tile_no_phone)
            state is ConnectionState.Online -> Tile.STATE_ACTIVE to state.phoneName
            state is ConnectionState.Working -> Tile.STATE_ACTIVE to getString(R.string.tile_connecting)
            state is ConnectionState.Failed -> Tile.STATE_INACTIVE to getString(R.string.tile_failed)
            else -> Tile.STATE_INACTIVE to getString(R.string.tile_tap)
        }
        tile.state = tileState
        tile.subtitle = sub
        tile.updateTile()
    }
}
