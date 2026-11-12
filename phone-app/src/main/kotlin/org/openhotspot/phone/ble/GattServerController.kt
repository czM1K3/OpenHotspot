package org.openhotspot.phone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.Proto
import org.openhotspot.common.Status
import org.openhotspot.common.phone.LogLevel
import org.openhotspot.common.phone.PhoneEngine
import org.openhotspot.phone.ServiceStatus
import org.openhotspot.phone.data.LogBuffer
import org.openhotspot.phone.permissions.BlePermissions

/**
 * Runs the BLE side of the phone: a GATT server with the three OpenHotspot characteristics and the
 * advertiser. All protocol decisions are made by [PhoneEngine]; this class only moves bytes.
 *
 * Untested against real hardware (Batch 3 could only compile it): see docs/DECISIONS.md.
 */
@SuppressLint("MissingPermission") // permissions are checked in start() via BlePermissions
class GattServerController(
    private val context: Context,
    private val engine: PhoneEngine,
    private val log: LogBuffer,
    private val status: MutableStateFlow<ServiceStatus>,
    private val scope: CoroutineScope,
) {
    private val serviceUuid = UUID.fromString(Proto.SERVICE_UUID)
    private val requestUuid = UUID.fromString(Proto.CHAR_REQUEST_UUID)
    private val responseUuid = UUID.fromString(Proto.CHAR_RESPONSE_UUID)
    private val statusUuid = UUID.fromString(Proto.CHAR_STATUS_UUID)
    private val cccdUuid = UUID.fromString(Proto.CCCD_UUID)

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var loop: Job? = null
    private var registered = false
    private val links = ConcurrentHashMap<String, DeviceLink>()
    private val notifyLock = Any() // pre-API-33 notify mutates the shared characteristic value

    @Volatile private var advertising = false
    @Volatile private var lastPayload: ByteArray? = null

    private class Outgoing(val characteristic: BluetoothGattCharacteristic?, val value: ByteArray, val disconnectAfter: Boolean = false)

    /** Per-connection state: ordered input, ordered output, one notification in flight at a time. */
    private inner class DeviceLink(val device: BluetoothDevice) {
        val link = engine.openLink()
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<Outgoing>(Channel.UNLIMITED)
        @Volatile var responseNotify = false
        @Volatile var statusNotify = false
        @Volatile var lastActivityMs = System.currentTimeMillis()
        @Volatile var pendingSent: CompletableDeferred<Unit>? = null
        private val jobs = mutableListOf<Job>()

        init {
            jobs += scope.launch { // process writes strictly in arrival order
                for (chunk in incoming) {
                    val notes = link.onWrite(chunk)
                    for (n in notes) outgoing.send(Outgoing(responseChar, n))
                    if (link.closeRequested) {
                        log.add(LogLevel.WARN, "Disconnecting a peer after repeated failures")
                        outgoing.send(Outgoing(null, ByteArray(0), disconnectAfter = true))
                    }
                }
            }
            jobs += scope.launch { // send notifications one at a time, waiting for onNotificationSent
                for (o in outgoing) {
                    if (o.disconnectAfter) {
                        server?.cancelConnection(device)
                        continue
                    }
                    val ch = o.characteristic ?: continue
                    val subscribed = if (ch.uuid == responseUuid) responseNotify else statusNotify
                    if (!subscribed) {
                        log.add(LogLevel.WARN, "Dropped a notification: the client has not enabled notifications")
                        continue
                    }
                    val waiter = CompletableDeferred<Unit>()
                    pendingSent = waiter
                    if (notify(device, ch, o.value)) withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { waiter.await() }
                }
            }
            jobs += scope.launch { // idle timeout: 10 s before authenticating, 30 s after (PROTOCOL.md section 7)
                while (isActive) {
                    delay(1000)
                    val limit = if (link.authenticated) 30_000 else 10_000
                    if (System.currentTimeMillis() - lastActivityMs > limit) {
                        log.add(LogLevel.INFO, "Closing an idle connection")
                        server?.cancelConnection(device)
                        break
                    }
                }
            }
        }

        fun close() {
            incoming.close(); outgoing.close()
            jobs.forEach { it.cancel() }
        }
    }

    // ---- lifecycle ------------------------------------------------------------------------

    fun start() {
        if (!registered) {
            context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            registered = true
        }
        openServer()
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(btStateReceiver) }
            registered = false
        }
        closeServer()
        status.update { it.copy(bluetooth = "Service stopped", advertising = false, connections = 0) }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> { log.add(LogLevel.INFO, "Bluetooth turned on"); openServer() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    log.add(LogLevel.WARN, "Bluetooth turned off")
                    closeServer()
                    status.update { it.copy(bluetooth = "Bluetooth is off", advertising = false, connections = 0) }
                }
            }
        }
    }

    @Synchronized
    private fun openServer() {
        if (server != null) return
        if (!BlePermissions.hasBluetooth(context)) {
            log.add(LogLevel.ERROR, "Bluetooth permission missing; open the app and grant it")
            status.update { it.copy(bluetooth = "Bluetooth permission missing") }
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            status.update { it.copy(bluetooth = "Bluetooth is off") }
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            log.add(LogLevel.ERROR, "This device cannot advertise over BLE")
            status.update { it.copy(bluetooth = "BLE advertising unsupported") }
            return
        }
        val srv = manager.openGattServer(context, callback)
        if (srv == null) {
            log.add(LogLevel.ERROR, "Could not open the GATT server")
            status.update { it.copy(bluetooth = "GATT server unavailable") }
            return
        }
        server = srv
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(requestUuid, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE),
        )
        responseChar = BluetoothGattCharacteristic(responseUuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
            .also(service::addCharacteristic)
        statusChar = BluetoothGattCharacteristic(
            statusUuid, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { addDescriptor(cccd()) }.also(service::addCharacteristic)
        if (!srv.addService(service)) {
            log.add(LogLevel.ERROR, "addService failed")
            closeServer()
            status.update { it.copy(bluetooth = "GATT service registration failed") }
        }
    }

    private fun cccd() = BluetoothGattDescriptor(cccdUuid, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)

    @Synchronized
    private fun closeServer() {
        loop?.cancel(); loop = null
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false; lastPayload = null; advertiser = null
        links.values.forEach { it.close() }; links.clear()
        runCatching { server?.close() }
        server = null; responseChar = null; statusChar = null
    }

    // ---- advertising loop -----------------------------------------------------------------

    /** Once a second: apply the current advertisement token (rotating through clients) and push Status changes. */
    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            var tick = 0L
            var lastStatus: Status? = null
            while (isActive) {
                applyAdvertisement(engine.advertisementPayload(tick++))
                val st = engine.status()
                if (st != lastStatus) {
                    if (lastStatus != null) notifyStatus(st)
                    lastStatus = st
                }
                delay(1000)
            }
        }
    }

    private fun applyAdvertisement(payload: ByteArray?) {
        if (payload.contentEqualsNullable(lastPayload) && (payload == null || advertising)) return
        val adv = advertiser ?: return
        if (advertising) {
            runCatching { adv.stopAdvertising(advertiseCallback) }
            advertising = false
        }
        lastPayload = payload
        if (payload == null) {
            status.update { it.copy(advertising = false, bluetooth = "Ready, nothing paired") }
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // No device name, no TX power, no manufacturer data: only the rotating token (PROTOCOL.md section 3).
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(serviceUuid), payload)
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            status.update { it.copy(advertising = true, bluetooth = "Advertising") }
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            lastPayload = null // retry on the next tick
            val name = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "internal error"
            }
            log.add(LogLevel.ERROR, "Advertising failed: $name ($errorCode)")
            status.update { it.copy(advertising = false, bluetooth = "Advertising failed: $name") }
        }
    }

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?) = if (this == null || other == null) this == other else contentEquals(other)

    // ---- notifications --------------------------------------------------------------------

    private fun notify(device: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val srv = server ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            srv.notifyCharacteristicChanged(device, ch, false, value) == BluetoothStatusCodes.SUCCESS
        } else synchronized(notifyLock) {
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            srv.notifyCharacteristicChanged(device, ch, false)
        }
    }

    private fun notifyStatus(st: Status) {
        val ch = statusChar ?: return
        for (dl in links.values) if (dl.statusNotify) dl.outgoing.trySend(Outgoing(ch, st.encode()))
    }

    // ---- GATT callbacks (binder threads: do the minimum, hand off to coroutines) -----------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(statusCode: Int, service: BluetoothGattService) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                log.add(LogLevel.INFO, "GATT service ready")
                startLoop()
            } else {
                log.add(LogLevel.ERROR, "GATT service registration failed ($statusCode)")
                status.update { it.copy(bluetooth = "GATT service registration failed") }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, statusCode: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // PROTOCOL/SECURITY: at most one unauthenticated peer is serviced at a time.
                if (links.values.any { !it.link.authenticated }) {
                    log.add(LogLevel.WARN, "Refused a second unauthenticated connection")
                    server?.cancelConnection(device)
                    return
                }
                links[addr] = DeviceLink(device)
                log.add(LogLevel.INFO, "Central connected")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (links.remove(addr)?.also { it.close() } != null) log.add(LogLevel.INFO, "Central disconnected")
            }
            status.update { it.copy(connections = links.size) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            links[device.address]?.link?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val dl = links[device.address]
            if (dl == null || characteristic.uuid != requestUuid || preparedWrite || offset != 0 || value == null) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            dl.lastActivityMs = System.currentTimeMillis()
            dl.incoming.trySend(value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val dl = links[device.address]
            if (dl == null || descriptor.uuid != cccdUuid || preparedWrite || offset != 0 || value == null || value.size != 2) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            when (descriptor.characteristic.uuid) {
                responseUuid -> dl.responseNotify = enabled
                statusUuid -> dl.statusNotify = enabled
            }
            dl.lastActivityMs = System.currentTimeMillis()
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val dl = links[device.address]
            val on = when (descriptor.characteristic.uuid) {
                responseUuid -> dl?.responseNotify == true
                statusUuid -> dl?.statusNotify == true
                else -> false
            }
            val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != statusUuid) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            val value = engine.status().encode()
            if (offset > value.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }

        override fun onNotificationSent(device: BluetoothDevice, statusCode: Int) {
            links[device.address]?.pendingSent?.complete(Unit)
        }
    }

    private companion object {
        const val NOTIFY_TIMEOUT_MS = 2_000L
    }
}
