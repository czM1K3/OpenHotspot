package org.openhotspot.phone.permissions

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlePermissionsTest {
    private val adv = Manifest.permission.BLUETOOTH_ADVERTISE
    private val connect = Manifest.permission.BLUETOOTH_CONNECT
    private val notif = Manifest.permission.POST_NOTIFICATIONS

    @Test fun android11NeedsNoRuntimePermission() {
        assertEquals(emptyList(), BlePermissions.allRuntime(30))
    }

    @Test fun android12AsksForAdvertiseAndConnectOnly() {
        for (sdk in 31..32) assertEquals(listOf(adv, connect), BlePermissions.allRuntime(sdk), "API $sdk")
    }

    @Test fun android13AddsNotifications() {
        for (sdk in 33..36) assertEquals(listOf(adv, connect, notif), BlePermissions.allRuntime(sdk), "API $sdk")
    }

    @Test fun scanIsNeverRequested() {
        for (sdk in 30..36) assertFalse(BlePermissions.allRuntime(sdk).contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(BlePermissions.bluetoothRuntime(33).none { it == notif })
    }
}
