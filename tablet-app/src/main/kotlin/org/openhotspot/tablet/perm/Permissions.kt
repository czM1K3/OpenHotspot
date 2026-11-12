package org.openhotspot.tablet.perm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions per API level. Pure functions of the SDK level, so they are unit tested.
 *
 * - API 30: scanning needs ACCESS_FINE_LOCATION (and location services switched on); connecting needs
 *   only the install-time BLUETOOTH permissions.
 * - API 31+: BLUETOOTH_SCAN (declared `neverForLocation`, so no location) and BLUETOOTH_CONNECT.
 * - API 33+: NEARBY_WIFI_DEVICES for Wi-Fi requests and POST_NOTIFICATIONS for the service notice.
 * - CAMERA only for pairing.
 */
object Permissions {
    fun bluetooth(sdkInt: Int): List<String> = when {
        sdkInt >= Build.VERSION_CODES.S -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun wifi(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()

    fun notifications(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Everything the connect button needs. */
    fun forConnecting(sdkInt: Int) = bluetooth(sdkInt) + wifi(sdkInt)

    /** The auto-trigger service additionally shows a notification. */
    fun forAutoService(sdkInt: Int) = forConnecting(sdkInt) + notifications(sdkInt)

    fun forPairing(sdkInt: Int) = forConnecting(sdkInt) + Manifest.permission.CAMERA

    fun missing(context: Context, wanted: List<String>): List<String> =
        wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun hasAll(context: Context, wanted: List<String>) = missing(context, wanted).isEmpty()
}
