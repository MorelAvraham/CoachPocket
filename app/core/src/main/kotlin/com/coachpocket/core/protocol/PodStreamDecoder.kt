package com.coachpocket.core.protocol

import com.coachpocket.core.imu.ImuSample

/** Extends a wrapping 32-bit microsecond counter (wraps every ~71.6 min) to a monotonic 64-bit one. */
class U32Unwrapper {
    private var last = Long.MIN_VALUE

    fun unwrap(raw: Long): Long {
        if (last == Long.MIN_VALUE) {
            last = raw and 0xFFFFFFFFL
            return last
        }
        val delta = ((raw and 0xFFFFFFFFL) - (last and 0xFFFFFFFFL)).toInt() // signed 32-bit difference
        val value = last + delta
        if (value > last) last = value
        return value
    }

    fun reset() {
        last = Long.MIN_VALUE
    }
}

class DecodedPodPacket(
    val seq: Int,
    /** Pod clock at the first sample, unwrapped, microseconds. */
    val t0PodUs: Long,
    val periodUs: Int,
    /** Packets missing between the previous packet and this one. */
    val lostBefore: Int,
    /** Samples in SI units, [ImuSample.tUs] on the pod clock. */
    val samples: List<ImuSample>,
) {
    val lastSamplePodUs: Long get() = t0PodUs + (samples.size - 1).toLong() * periodUs
}

/**
 * Stateful decoder for the IMU notification stream: converts to SI units, unwraps the pod clock
 * and keeps the loss counters reported for requirement R1 / test U1.
 */
class PodStreamDecoder {
    var packetsReceived = 0L; private set
    var packetsLost = 0L; private set
    var packetsLate = 0L; private set
    var packetsMalformed = 0L; private set
    var samplesReceived = 0L; private set
    var lastPeriodUs = 0; private set

    private var expectedSeq = -1
    private val clock = U32Unwrapper()

    /** Fraction of packets the pod sent that never arrived. */
    val lossRatio: Double
        get() {
            val total = packetsReceived + packetsLost
            return if (total == 0L) 0.0 else packetsLost.toDouble() / total
        }

    fun reset() {
        packetsReceived = 0; packetsLost = 0; packetsLate = 0; packetsMalformed = 0; samplesReceived = 0
        expectedSeq = -1
        clock.reset()
    }

    fun decode(bytes: ByteArray): DecodedPodPacket? {
        val raw = PodPacketCodec.decode(bytes)
        if (raw == null) {
            packetsMalformed++
            return null
        }
        var lost = 0
        if (expectedSeq >= 0) {
            val gap = (raw.seq - expectedSeq) and 0xFFFF
            if (gap >= 0x8000) {
                // Duplicate or out-of-order packet from the past: ignore, do not disturb the counters.
                packetsLate++
                return null
            }
            lost = gap
        }
        expectedSeq = (raw.seq + 1) and 0xFFFF
        packetsLost += lost
        packetsReceived++
        samplesReceived += raw.sampleCount
        lastPeriodUs = raw.periodUs

        val t0 = clock.unwrap(raw.t0Us)
        val aScale = PodProtocol.accelScale(raw.accelFs)
        val gScale = PodProtocol.gyroScale(raw.gyroFs)
        val r = raw.raw
        val samples = ArrayList<ImuSample>(raw.sampleCount)
        for (i in 0 until raw.sampleCount) {
            val o = i * 6
            samples += ImuSample(
                tUs = t0 + i.toLong() * raw.periodUs,
                ax = r[o] * aScale, ay = r[o + 1] * aScale, az = r[o + 2] * aScale,
                gx = r[o + 3] * gScale, gy = r[o + 4] * gScale, gz = r[o + 5] * gScale,
            )
        }
        return DecodedPodPacket(raw.seq, t0, raw.periodUs, lost, samples)
    }

    /** Unwraps a 32-bit pod timestamp from a sync response against the data-stream clock. */
    fun unwrapPodTime(rawUs: Long): Long = clock.unwrap(rawUs)
}
