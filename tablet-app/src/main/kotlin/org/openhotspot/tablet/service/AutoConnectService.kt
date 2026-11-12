package org.openhotspot.tablet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import android.util.Log
import kotlinx.coroutines.launch
import org.openhotspot.tablet.MainActivity
import org.openhotspot.tablet.R
import org.openhotspot.tablet.graph

/**
 * Foreground service (type connectedDevice) that runs the auto-trigger. It exists only while the
 * setting is on: a foreground service is what lets Android keep scanning with the screen off, and it
 * costs a permanent notification (PROTOCOL.md R4, SETUP.md).
 */
class AutoConnectService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        val g = graph
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) { // missing Bluetooth permission on API 34+, or a background-start restriction
            Log.w(TAG, "Could not start in the foreground (${e.javaClass.simpleName}); open the app and re-enable auto-connect")
            g.settings.update { it.copy(autoEnabled = false) }
            stopSelf()
            return
        }
        g.networkMonitor.start()
        lifecycleScope.launch { g.auto.run() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.settings.update { it.copy(autoEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        graph.networkMonitor.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutoConnectService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
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
        private const val TAG = "OpenHotspot"
        private const val CHANNEL_ID = "auto"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.openhotspot.tablet.STOP_AUTO"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutoConnectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoConnectService::class.java))
        }
    }
}
