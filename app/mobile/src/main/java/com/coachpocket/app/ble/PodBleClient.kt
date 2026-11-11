package com.coachpocket.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.coachpocket.app.nowUs
import com.coachpocket.core.protocol.PodPacketCodec
import com.coachpocket.core.protocol.PodProtocol
import java.util.UUID

enum class PodState { IDLE, SCANNING, CONNECTING, STREAMING, SIMULATED }

/**
 * BLE central for the wrist pod. Scans for the CoachPocket service, connects, asks for the link
 * parameters the 1 kHz stream needs (MTU 247, 2M PHY, high connection priority), subscribes to
 * the IMU notifications and runs the time beacon once a second.
 *
 * Callers must hold BLUETOOTH_SCAN and BLUETOOTH_CONNECT before calling [connect].
 */
@SuppressLint("MissingPermission")
class PodBleClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onPodState(state: PodState, message: String?)

        /** Called on a Bluetooth binder thread. [rxNs] is elapsedRealtimeNanos at delivery. */
        fun onImuPacket(bytes: ByteArray, rxNs: Long)

        fun onSyncResponse(bytes: ByteArray, rxNs: Long)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var syncCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false

    fun connect() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            listener.onPodState(PodState.IDLE, "Bluetooth is off")
            return
        }
        disconnect()
        scanning = true
        listener.onPodState(PodState.SCANNING, null)
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun disconnect() {
        stopScan()
        handler.removeCallbacks(syncPing)
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        syncCharacteristic = null
        listener.onPodState(PodState.IDLE, null)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        listener.onPodState(PodState.IDLE, "No pod found")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            listener.onPodState(PodState.CONNECTING, result.device.address)
            gatt = result.device.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_2M_MASK or BluetoothDevice.PHY_LE_1M_MASK,
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            listener.onPodState(PodState.IDLE, "Scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                if (!g.requestMtu(PodProtocol.REQUESTED_MTU)) g.discoverServices()
            } else {
                handler.post {
                    handler.removeCallbacks(syncPing)
                    if (gatt === g) {
                        g.close()
                        gatt = null
                        syncCharacteristic = null
                        listener.onPodState(PodState.IDLE, "Pod disconnected (status $status)")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val data = g.getService(SERVICE)?.getCharacteristic(IMU_DATA)
            if (status != BluetoothGatt.GATT_SUCCESS || data == null) {
                fail(g, "Pod does not expose the IMU characteristic")
                return
            }
            if (!subscribe(g, data)) fail(g, "Could not subscribe to IMU data")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(g, "Subscription rejected ($status)")
                return
            }
            val sync = g.getService(SERVICE)?.getCharacteristic(SYNC)
            if (descriptor.characteristic.uuid == IMU_DATA && sync != null) {
                // GATT operations are strictly one at a time: subscribe to the beacon after the data.
                if (subscribe(g, sync)) return
            }
            if (descriptor.characteristic.uuid == SYNC) {
                syncCharacteristic = descriptor.characteristic
                handler.post(syncPing)
            }
            listener.onPodState(PodState.STREAMING, if (sync == null) "no time beacon" else null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(c.uuid, value, SystemClock.elapsedRealtimeNanos())
        }

        // Android 12 calls this overload; Android 13+ calls the one above.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val rxNs = SystemClock.elapsedRealtimeNanos()
            dispatch(c.uuid, c.value?.clone() ?: return, rxNs)
        }
    }

    private fun dispatch(uuid: UUID, value: ByteArray, rxNs: Long) {
        when (uuid) {
            IMU_DATA -> listener.onImuPacket(value, rxNs)
            SYNC -> listener.onSyncResponse(value, rxNs)
        }
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val cccd = c.getDescriptor(CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    private fun fail(g: BluetoothGatt, message: String) {
        handler.post {
            g.disconnect()
            g.close()
            if (gatt === g) gatt = null
            listener.onPodState(PodState.IDLE, message)
        }
    }

    private val syncPing = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            val c = syncCharacteristic ?: return
            val request = PodPacketCodec.encodeSyncRequest(nowUs())
            val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, request, type)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = request
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString(PodProtocol.SERVICE_UUID)
        val IMU_DATA: UUID = UUID.fromString(PodProtocol.IMU_DATA_UUID)
        val SYNC: UUID = UUID.fromString(PodProtocol.SYNC_UUID)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SCAN_TIMEOUT_MS = 15_000L
        const val SYNC_INTERVAL_MS = 1_000L
    }
}
