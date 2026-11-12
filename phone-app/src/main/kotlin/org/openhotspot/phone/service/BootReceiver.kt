package org.openhotspot.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.phone.graph
import org.openhotspot.phone.permissions.BlePermissions

/**
 * Restarts the service after boot or an app update if the user had it running and allows
 * start-on-boot. BOOT_COMPLETED is exempt from the Android 12+ background foreground-service
 * start restrictions, and connectedDevice is not one of the types Android 15 blocks from there.
 * Does nothing if Bluetooth permissions are missing (it cannot ask).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val g = context.graph
        if (!g.settings.serviceEnabled || !g.settings.startOnBoot) return
        if (!BlePermissions.hasBluetooth(context)) {
            g.log.add(LogLevel.WARN, "Not starting after boot: Bluetooth permission missing")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, HotspotService::class.java))
            g.log.add(LogLevel.INFO, "Started after boot")
        } catch (e: Exception) {
            g.log.add(LogLevel.ERROR, "Could not start after boot (${e.javaClass.simpleName})")
        }
    }
}
