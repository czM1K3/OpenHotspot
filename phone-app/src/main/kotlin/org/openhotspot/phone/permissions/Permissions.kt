package org.openhotspot.phone.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime permissions per API level.
 *
 * - API 30 (Android 11): advertising and a GATT server only need the install-time BLUETOOTH and
 *   BLUETOOTH_ADMIN permissions (declared with maxSdkVersion 30). No runtime prompt.
 * - API 31+ : BLUETOOTH_ADVERTISE (advertise) and BLUETOOTH_CONNECT (GATT server connections).
 * - API 33+ : POST_NOTIFICATIONS for the foreground-service notification.
 *
 * BLUETOOTH_SCAN is deliberately NOT requested: the phone never scans, and on API 30 scanning
 * would drag in location permission. FOREGROUND_SERVICE_CONNECTED_DEVICE (API 34) is a normal
 * install-time permission, but the system also requires one of the Bluetooth permissions above
 * to be held when a connectedDevice service starts.
 */
object BlePermissions {
    fun bluetoothRuntime(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyList()

    fun notificationRuntime(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Everything to request in one go on this device. */
    fun allRuntime(sdkInt: Int): List<String> = bluetoothRuntime(sdkInt) + notificationRuntime(sdkInt)

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun hasBluetooth(context: Context, sdkInt: Int = Build.VERSION.SDK_INT) = bluetoothRuntime(sdkInt).all { granted(context, it) }

    fun hasNotifications(context: Context, sdkInt: Int = Build.VERSION.SDK_INT) = notificationRuntime(sdkInt).all { granted(context, it) }

    fun missing(context: Context, sdkInt: Int = Build.VERSION.SDK_INT) = allRuntime(sdkInt).filterNot { granted(context, it) }
}

object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Asks the system to exempt this app (a one-tap dialog; needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Fallback: the full list, in case the direct dialog is unavailable on a vendor ROM. */
    fun settingsListIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
