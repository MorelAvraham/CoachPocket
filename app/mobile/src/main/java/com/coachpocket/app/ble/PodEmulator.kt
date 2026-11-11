package com.coachpocket.app.ble

import android.annotation.SuppressLint
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
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import com.coachpocket.core.protocol.PodPacketCodec
import com.coachpocket.core.protocol.PodProtocol
import com.coachpocket.core.protocol.RawPodPacket
import java.util.UUID
import kotlin.math.roundToInt

data class EmulatorStats(
    val running: Boolean = false,
    val central: String? = null,
    val subscribed: Boolean = false,
    val sampleRateHz: Float = 0f,
    val packetsSent: Long = 0,
    val packetsDropped: Long = 0,
    val message: String? = null,
)

/**
 * Turns this phone into a stand-in for the wrist pod: a BLE peripheral that exposes the CoachPocket
 * service and streams the phone's own accelerometer and gyroscope in the pod packet format,
 * including the time beacon. Strap it to the wrist and the coach app on a second phone sees a pod.
 *
 * This lets the BLE link, clock sync and fusion path be developed and measured before the real pod
 * exists. It is not a substitute for it: phone IMUs deliver roughly 200 to 500 Hz, not 1 kHz, and a
 * phone on the wrist weighs far more than the pod will.
 *
 * Caller must hold BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class PodEmulator(private val context: Context, private val onStats: (EmulatorStats) -> Unit) : SensorEventListener {
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)
    private val sensors get() = context.getSystemService(SensorManager::class.java)

    private var server: BluetoothGattServer? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var syncChar: BluetoothGattCharacteristic? = null
    private var sensorThread: HandlerThread? = null

    private val lock = Any()
    private val queue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var inFlight = false
    private var central: BluetoothDevice? = null
    private var dataSubscribed = false
    private var syncSubscribed = false
    private var maxSamples = PodProtocol.MAX_SAMPLES_PER_PACKET

    // Sensor thread only.
    private val gyro = FloatArray(3)
    private var raw = ShortArray(PodProtocol.MAX_SAMPLES_PER_PACKET * 6)
    private var count = 0
    private var packetT0Us = 0L
    private var lastSampleUs = 0L
    private var periodUs = 5000.0
    private var seq = 0
    private var sent = 0L
    private var dropped = 0L
    private var lastStatsMs = 0L
    private var message: String? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (isRunning) return
        val adapter = bluetooth?.adapter
        val advertiser = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        when {
            advertiser == null -> return report("Bluetooth is off, or this phone cannot act as a BLE peripheral")
            accel == null || gyroSensor == null -> return report("This phone has no accelerometer or gyroscope")
        }
        val gattServer = bluetooth?.openGattServer(context, serverCallback) ?: return report("Could not open a GATT server")
        server = gattServer

        val cccd = { BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE) }
        val data = BluetoothGattCharacteristic(IMU_DATA, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply { addDescriptor(cccd()) }
        val sync = BluetoothGattCharacteristic(
            SYNC,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply { addDescriptor(cccd()) }
        dataChar = data; syncChar = sync
        gattServer.addService(
            BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(data); addCharacteristic(sync)
            },
        )

        advertiser!!.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build(),
            // A 128-bit service UUID fills most of the 31-byte advertisement: leave the name out.
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),
            advertiseCallback,
        )

        val thread = HandlerThread("pod-emulator").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        count = 0; seq = 0; sent = 0; dropped = 0; lastSampleUs = 0
        periodUs = accel!!.minDelay.coerceAtLeast(1000).toDouble()
        sensors?.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
        sensors?.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        message = "Advertising. Press \"Connect pod\" on the other phone."
        publish(force = true)
    }

    fun stop() {
        if (!isRunning) return
        sensors?.unregisterListener(this)
        sensorThread?.quitSafely(); sensorThread = null
        bluetooth?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        server?.close(); server = null
        synchronized(lock) {
            queue.clear(); inFlight = false; central = null; dataSubscribed = false; syncSubscribed = false
        }
        onStats(EmulatorStats(message = "Stopped"))
    }

    private fun report(text: String) {
        onStats(EmulatorStats(message = text))
    }

    // ---- Sensors -> packets ----------------------------------------------------------------

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gyro[0] = event.values[0]; gyro[1] = event.values[1]; gyro[2] = event.values[2]
            return
        }
        // One pod sample per accelerometer event, paired with the latest gyroscope reading.
        val tUs = event.timestamp / 1000
        if (lastSampleUs != 0L) {
            val dt = (tUs - lastSampleUs).toDouble()
            if (dt > 0 && dt < 100_000) periodUs += 0.01 * (dt - periodUs)
        }
        lastSampleUs = tUs
        if (count == 0) packetT0Us = tUs
        val o = count * 6
        val aScale = PodProtocol.accelScale(ACCEL_FS)
        val gScale = PodProtocol.gyroScale(GYRO_FS)
        for (k in 0 until 3) {
            raw[o + k] = (event.values[k] / aScale).roundToInt().coerceIn(-32768, 32767).toShort()
            raw[o + 3 + k] = (gyro[k] / gScale).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        count++
        // Aim for a packet every ~20 ms, like the real pod, whatever rate this phone's IMU delivers.
        val target = (20_000 / periodUs).roundToInt().coerceIn(1, synchronized(lock) { maxSamples })
        if (count >= target) {
            val packet = RawPodPacket(seq, packetT0Us and 0xFFFFFFFFL, periodUs.roundToInt().coerceIn(1, 0xFFFF), ACCEL_FS, GYRO_FS, raw.copyOf(count * 6))
            seq = (seq + 1) and 0xFFFF // counts every packet built, sent or not, so the receiver sees loss
            count = 0
            enqueue(dataChar ?: return, PodPacketCodec.encode(packet), isData = true)
        }
        publish(force = false)
    }

    // ---- Notification queue: one notification in flight at a time ----------------------------

    private fun enqueue(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, isData: Boolean) {
        synchronized(lock) {
            val subscribed = if (isData) dataSubscribed else syncSubscribed
            if (central == null || !subscribed) return
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(characteristic to bytes)
        }
        pump()
    }

    private fun pump() {
        val gattServer = server ?: return
        synchronized(lock) {
            while (!inFlight) {
                val device = central ?: return
                val (characteristic, bytes) = queue.removeFirstOrNull() ?: return
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServer.notifyCharacteristicChanged(device, characteristic, false, bytes) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    @Suppress("DEPRECATION")
                    gattServer.notifyCharacteristicChanged(device, characteristic, false)
                }
                if (ok) {
                    inFlight = true
                    if (characteristic.uuid == IMU_DATA) sent++
                } else {
                    dropped++
                }
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(lock) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    central = device
                    message = "Connected"
                } else if (central?.address == device.address) {
                    central = null; dataSubscribed = false; syncSubscribed = false
                    queue.clear(); inFlight = false
                    maxSamples = PodProtocol.MAX_SAMPLES_PER_PACKET
                    message = "Central disconnected. Still advertising."
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(lock) {
                maxSamples = ((mtu - 3 - PodProtocol.HEADER_BYTES) / PodProtocol.SAMPLE_BYTES).coerceIn(1, PodProtocol.MAX_SAMPLES_PER_PACKET)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) {
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                synchronized(lock) {
                    if (descriptor.characteristic.uuid == IMU_DATA) dataSubscribed = enabled else syncSubscribed = enabled
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val subscribed = synchronized(lock) { if (descriptor.characteristic.uuid == IMU_DATA) dataSubscribed else syncSubscribed }
            val value = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val t2 = podClockUs()
            if (characteristic.uuid == SYNC && value.size == 8) {
                var t1 = 0L
                for (i in 0 until 8) t1 = t1 or ((value[i].toLong() and 0xFF) shl (8 * i))
                enqueue(characteristic, PodPacketCodec.encodeSyncResponse(t1, t2, podClockUs()), isData = false)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) { inFlight = false }
            pump()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            message = "Advertising failed ($errorCode)"
            publish(force = true)
        }
    }

    /** Same clock as the sensor timestamps (time since boot), wrapped to 32 bits like the pod's. */
    private fun podClockUs(): Long = (SystemClock.elapsedRealtimeNanos() / 1000) and 0xFFFFFFFFL

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatsMs < 500) return
        lastStatsMs = now
        val (device, subscribed) = synchronized(lock) { central to dataSubscribed }
        onStats(EmulatorStats(isRunning, device?.address, subscribed, (1e6 / periodUs).toFloat(), sent, dropped, message))
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString(PodProtocol.SERVICE_UUID)
        val IMU_DATA: UUID = UUID.fromString(PodProtocol.IMU_DATA_UUID)
        val SYNC: UUID = UUID.fromString(PodProtocol.SYNC_UUID)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val ACCEL_FS = 3 // +-16 g
        const val GYRO_FS = 3 // +-2000 deg/s
        const val MAX_QUEUE = 64
    }
}
