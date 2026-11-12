package org.openhotspot.tablet.ble

import android.bluetooth.BluetoothStatusCodes
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.openhotspot.common.Proto
import org.openhotspot.tablet.perm.Permissions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [BleTransport] on the platform BLE APIs (no Google Play services): `BluetoothLeScanner` and
 * `BluetoothGatt`. Everything here needs a real phone to be exercised; the protocol above it is tested
 * against an in-memory transport (see docs/TESTING.md for the hardware checklist).
 */
@SuppressLint("MissingPermission") // checked explicitly (permissionsOk) and SecurityException is translated
class GattTransport(private val context: Context) : BleTransport {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun requireAdapter(): BluetoothAdapter {
        val a = adapter
        if (a == null || !a.isEnabled) throw BleException(BleProblem.BLUETOOTH_OFF, "Bluetooth is off")
        return a
    }

    private fun requirePermissions() {
        val missing = Permissions.missing(context, Permissions.bluetooth(Build.VERSION.SDK_INT))
        if (missing.isNotEmpty()) {
            throw BleException(BleProblem.PERMISSION, "Allow \"Nearby devices\" (Bluetooth) for this app" +
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " and Location" else "")
        }
    }

    override suspend fun <T : Any> scan(timeoutMs: Long, accept: (Sighting) -> T?): T? {
        requirePermissions()
        val scanner = requireAdapter().bluetoothLeScanner ?: throw BleException(BleProblem.BLUETOOTH_OFF, "Bluetooth is off")
        val uuid = ParcelUuid(UUID.fromString(Proto.SERVICE_UUID))
        // Hardware-friendly filter: our service data key, and the high nibble of byte 0 (the protocol version) must be 1.
        val filter = ScanFilter.Builder().setServiceData(uuid, byteArrayOf(0x10), byteArrayOf(0xF0.toByte())).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        var callback: ScanCallback? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<T> { cont ->
                    val cb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            val data = result.scanRecord?.getServiceData(uuid) ?: return
                            val hit = accept(Sighting(result.device.address, data, result.rssi)) ?: return
                            if (cont.isActive) cont.resume(hit)
                        }

                        override fun onScanFailed(errorCode: Int) {
                            if (cont.isActive) cont.resumeWithException(
                                BleException(BleProblem.SCAN_FAILED, "Android could not start the scan (error $errorCode)" +
                                    if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED || errorCode == 6) // 6 = SCANNING_TOO_FREQUENTLY
                                        ". Android limits how often an app may start scans; wait 30 seconds." else "",
                                    retryable = false),
                            )
                        }
                    }
                    callback = cb
                    try {
                        scanner.startScan(listOf(filter), settings, cb)
                    } catch (e: SecurityException) {
                        cont.resumeWithException(BleException(BleProblem.PERMISSION, "Bluetooth permission missing", cause = e))
                    } catch (e: IllegalStateException) {
                        cont.resumeWithException(BleException(BleProblem.BLUETOOTH_OFF, "Bluetooth is off", cause = e))
                    }
                }
            }
        } finally {
            callback?.let { runCatching { scanner.stopScan(it) } }
        }
    }

    override suspend fun connect(address: String): BleLink {
        requirePermissions()
        val device = try {
            requireAdapter().getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw BleException(BleProblem.CONNECT_FAILED, "Bad Bluetooth address", cause = e)
        }
        val link = GattLink(context, device)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { link.open() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            link.close()
            throw BleException(BleProblem.CONNECT_FAILED, "Connecting timed out", retryable = true, cause = e)
        } catch (e: Throwable) {
            link.close()
            throw e
        }
        return link
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val REQUESTED_MTU = 247
    }
}

@SuppressLint("MissingPermission")
private class GattLink(private val context: Context, private val device: BluetoothDevice) : BleLink {
    private val serviceUuid = UUID.fromString(Proto.SERVICE_UUID)
    private val requestUuid = UUID.fromString(Proto.CHAR_REQUEST_UUID)
    private val responseUuid = UUID.fromString(Proto.CHAR_RESPONSE_UUID)
    private val cccdUuid = UUID.fromString(Proto.CCCD_UUID)

    @Volatile override var mtu: Int = Proto.MIN_MTU
        private set

    private var gatt: BluetoothGatt? = null
    private var request: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Unit>()
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    @Volatile private var pendingWrite: CompletableDeferred<Int>? = null
    @Volatile private var closed = false

    suspend fun open() {
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw BleException(BleProblem.PERMISSION, "Bluetooth permission missing", cause = e)
        } ?: throw BleException(BleProblem.CONNECT_FAILED, "Android would not start a connection", retryable = true)
        ready.await()
    }

    private fun fail(e: BleException) {
        ready.completeExceptionally(e)
        pendingWrite?.completeExceptionally(e)
        notifications.close(e)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(GattTransport.REQUESTED_MTU)) g.discoverServices() // MTU stays 23 (PROTOCOL.md R13)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                // Status 133 (GATT_ERROR) is the usual "could not connect" and is worth one fresh attempt.
                fail(BleException(
                    if (ready.isCompleted) BleProblem.DISCONNECTED else BleProblem.CONNECT_FAILED,
                    "Bluetooth link ended (status $status)", retryable = true,
                ))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newMtu >= Proto.MIN_MTU) mtu = newMtu
            if (!g.discoverServices()) fail(BleException(BleProblem.CONNECT_FAILED, "Service discovery would not start", retryable = true))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(serviceUuid)
            val req = service?.getCharacteristic(requestUuid)
            val resp = service?.getCharacteristic(responseUuid)
            val cccd = resp?.getDescriptor(cccdUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || req == null || resp == null || cccd == null) {
                fail(BleException(BleProblem.SERVICE_MISSING, "OpenHotspot service not found (status $status)"))
                return
            }
            request = req
            if (!g.setCharacteristicNotification(resp, true) || !writeCccd(g, cccd)) {
                fail(BleException(BleProblem.CONNECT_FAILED, "Could not enable notifications", retryable = true))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else fail(BleException(BleProblem.CONNECT_FAILED, "Enabling notifications failed (status $status)", retryable = true))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status)
        }

        // API 33+ delivers the value as a parameter; older versions only call the deprecated overload below.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == responseUuid) notifications.trySend(value.copyOf())
        }

        @Deprecated("Used on API 30 to 32")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && c.uuid == responseUuid) notifications.trySend(c.value.copyOf())
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCccd(g: BluetoothGatt, cccd: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

    @Suppress("DEPRECATION")
    override suspend fun write(chunk: ByteArray) = writeLock.withLock {
        val g = gatt ?: throw BleException(BleProblem.DISCONNECTED, "Not connected", retryable = true)
        val c = request ?: throw BleException(BleProblem.DISCONNECTED, "Not connected", retryable = true)
        val done = CompletableDeferred<Int>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Write With Response, as the protocol requires
            c.value = chunk
            g.writeCharacteristic(c)
        }
        if (!started) throw BleException(BleProblem.CONNECT_FAILED, "Android refused the write", retryable = true)
        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
            ?: throw BleException(BleProblem.TIMEOUT, "The phone did not acknowledge a write", retryable = true)
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleException(BleProblem.CONNECT_FAILED, "Write failed (status $status)", retryable = true)
    }

    override suspend fun receive(timeoutMs: Long): ByteArray = try {
        withTimeoutOrNull(timeoutMs) { notifications.receive() }
            ?: throw BleException(BleProblem.TIMEOUT, "The phone did not answer in time")
    } catch (e: ClosedReceiveChannelException) {
        throw BleException(BleProblem.DISCONNECTED, "The link to the phone dropped", retryable = true, cause = e)
    }

    override fun close() {
        if (closed) return
        closed = true
        notifications.close()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000L
    }
}
