package com.coachpocket.core.protocol

/**
 * Wire contract between the wrist pod (firmware/) and the phone. See docs/pod-ble-protocol.md.
 *
 * IMU data packet, little-endian, sent as a GATT notification on [IMU_DATA_UUID]:
 *
 * | off | type | field                                                               |
 * |-----|------|---------------------------------------------------------------------|
 * | 0   | u16  | seq: packet counter, wraps at 65536                                 |
 * | 2   | u8   | n: samples in this packet, 1..19                                    |
 * | 3   | u8   | flags: bits0-1 accel FS, bits2-3 gyro FS, bits4-7 protocol version  |
 * | 4   | u32  | t0: pod clock at the FIRST sample, microseconds, wraps at 2^32      |
 * | 8   | u16  | period: sample period, microseconds (1000 at 1 kHz)                 |
 * | 10  | i16 x 6 x n | ax ay az gx gy gz raw counts, full scale = 32768             |
 *
 * 19 samples x 12 B + 10 B = 238 B, which fits one notification at ATT MTU 247 (244 B payload).
 */
object PodProtocol {
    const val VERSION = 1

    const val SERVICE_UUID = "c0ac0001-9f6b-4a3e-8d57-1e2b3c4d5e6f"

    /** Notify. IMU data packets as described above. */
    const val IMU_DATA_UUID = "c0ac0002-9f6b-4a3e-8d57-1e2b3c4d5e6f"

    /**
     * Write-without-response + notify. Two-way time beacon.
     * Phone writes 8 B: u64 t1 (phone clock, us).
     * Pod notifies 16 B: u64 t1 (echo), u32 t2 (pod clock at receive), u32 t3 (pod clock at send).
     */
    const val SYNC_UUID = "c0ac0003-9f6b-4a3e-8d57-1e2b3c4d5e6f"

    const val HEADER_BYTES = 10
    const val SAMPLE_BYTES = 12
    const val MAX_SAMPLES_PER_PACKET = 19
    const val REQUESTED_MTU = 247

    const val STANDARD_GRAVITY = 9.80665f

    /** Accelerometer full scale in g, indexed by the 2-bit code in the flags byte. */
    val ACCEL_FS_G = intArrayOf(2, 4, 8, 16)

    /** Gyroscope full scale in deg/s, indexed by the 2-bit code in the flags byte. */
    val GYRO_FS_DPS = intArrayOf(250, 500, 1000, 2000)

    fun accelScale(fsCode: Int): Float = ACCEL_FS_G[fsCode] * STANDARD_GRAVITY / 32768f

    fun gyroScale(fsCode: Int): Float = (GYRO_FS_DPS[fsCode] * Math.PI / 180.0 / 32768.0).toFloat()
}

/** A packet exactly as it is on the wire: raw counts and the wrapped 32-bit pod timestamp. */
class RawPodPacket(
    val seq: Int,
    val t0Us: Long,
    val periodUs: Int,
    val accelFs: Int,
    val gyroFs: Int,
    /** 6 values per sample: ax ay az gx gy gz. */
    val raw: ShortArray,
) {
    val sampleCount: Int get() = raw.size / 6
}

object PodPacketCodec {
    fun encode(packet: RawPodPacket): ByteArray {
        val n = packet.sampleCount
        require(n in 1..PodProtocol.MAX_SAMPLES_PER_PACKET && packet.raw.size == n * 6) { "bad sample count" }
        require(packet.periodUs in 1..0xFFFF) { "bad period" }
        val out = ByteArray(PodProtocol.HEADER_BYTES + n * PodProtocol.SAMPLE_BYTES)
        putU16(out, 0, packet.seq and 0xFFFF)
        out[2] = n.toByte()
        out[3] = ((packet.accelFs and 3) or ((packet.gyroFs and 3) shl 2) or (PodProtocol.VERSION shl 4)).toByte()
        val t = packet.t0Us and 0xFFFFFFFFL
        putU16(out, 4, (t and 0xFFFF).toInt())
        putU16(out, 6, ((t ushr 16) and 0xFFFF).toInt())
        putU16(out, 8, packet.periodUs)
        var o = PodProtocol.HEADER_BYTES
        for (v in packet.raw) {
            putU16(out, o, v.toInt() and 0xFFFF)
            o += 2
        }
        return out
    }

    /** Returns null for anything that is not a well-formed packet of the supported version. */
    fun decode(bytes: ByteArray): RawPodPacket? {
        if (bytes.size < PodProtocol.HEADER_BYTES + PodProtocol.SAMPLE_BYTES) return null
        val n = bytes[2].toInt() and 0xFF
        val flags = bytes[3].toInt() and 0xFF
        if (flags ushr 4 != PodProtocol.VERSION) return null
        if (n !in 1..PodProtocol.MAX_SAMPLES_PER_PACKET) return null
        if (bytes.size != PodProtocol.HEADER_BYTES + n * PodProtocol.SAMPLE_BYTES) return null
        val period = getU16(bytes, 8)
        if (period == 0) return null
        val t0 = getU16(bytes, 4).toLong() or (getU16(bytes, 6).toLong() shl 16)
        val raw = ShortArray(n * 6)
        var o = PodProtocol.HEADER_BYTES
        for (i in raw.indices) {
            raw[i] = getU16(bytes, o).toShort()
            o += 2
        }
        return RawPodPacket(getU16(bytes, 0), t0, period, flags and 3, (flags ushr 2) and 3, raw)
    }

    fun encodeSyncRequest(t1PhoneUs: Long): ByteArray = ByteArray(8).also { putU64(it, 0, t1PhoneUs) }

    /** @return (t1 echoed phone time, t2 pod rx, t3 pod tx), pod times still wrapped to 32 bits. */
    fun decodeSyncResponse(bytes: ByteArray): Triple<Long, Long, Long>? {
        if (bytes.size != 16) return null
        return Triple(getU64(bytes, 0), getU32(bytes, 8), getU32(bytes, 12))
    }

    fun encodeSyncResponse(t1PhoneUs: Long, t2PodUs: Long, t3PodUs: Long): ByteArray {
        val out = ByteArray(16)
        putU64(out, 0, t1PhoneUs)
        putU16(out, 8, (t2PodUs and 0xFFFF).toInt()); putU16(out, 10, ((t2PodUs ushr 16) and 0xFFFF).toInt())
        putU16(out, 12, (t3PodUs and 0xFFFF).toInt()); putU16(out, 14, ((t3PodUs ushr 16) and 0xFFFF).toInt())
        return out
    }

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
    }

    private fun getU16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun getU32(b: ByteArray, o: Int): Long = getU16(b, o).toLong() or (getU16(b, o + 2).toLong() shl 16)

    private fun putU64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
    }

    private fun getU64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
