package org.openhotspot.tablet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.openhotspot.tablet.graph
import org.openhotspot.tablet.perm.Permissions

/** Restarts the auto-connect service after a reboot or an app update, only if the user left it on and the permissions are held. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val g = context.graph
        if (g.settings.settings.value.autoEnabled && Permissions.hasAll(context, Permissions.forConnecting(android.os.Build.VERSION.SDK_INT))) {
            runCatching { AutoConnectService.start(context) }
        }
    }
}
