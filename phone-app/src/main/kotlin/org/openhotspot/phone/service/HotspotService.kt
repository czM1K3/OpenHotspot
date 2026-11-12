package org.openhotspot.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.update
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.phone.MainActivity
import org.openhotspot.phone.R
import org.openhotspot.phone.ble.GattServerController
import org.openhotspot.phone.graph

/** Foreground service (type connectedDevice) that keeps the GATT server and advertiser alive. */
class HotspotService : LifecycleService() {
    private var controller: GattServerController? = null

    override fun onCreate() {
        super.onCreate()
        val g = graph
        createChannel()
        try {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) { // e.g. missing Bluetooth permission on API 34+, or background-start restriction
            g.log.add(LogLevel.ERROR, "Could not start in the foreground (${e.javaClass.simpleName}); grant permissions and start from the app")
            stopSelf()
            return
        }
        controller = GattServerController(this, g.engine, g.log, g.serviceStatus, lifecycleScope).also { it.start() }
        g.serviceStatus.update { it.copy(running = true) }
        g.log.add(LogLevel.INFO, "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.settings.serviceEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY // restart after the system kills the process
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        graph.serviceStatus.update { it.copy(running = false, advertising = false, connections = 0) }
        graph.log.add(LogLevel.INFO, "Service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HotspotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hotspot)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.openhotspot.phone.STOP"

        fun start(context: Context) {
            context.graph.settings.serviceEnabled = true
            ContextCompat.startForegroundService(context, Intent(context, HotspotService::class.java))
        }

        fun stop(context: Context) {
            context.graph.settings.serviceEnabled = false
            context.stopService(Intent(context, HotspotService::class.java))
        }
    }
}
