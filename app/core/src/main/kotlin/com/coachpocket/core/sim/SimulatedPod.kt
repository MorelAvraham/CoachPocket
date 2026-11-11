package com.coachpocket.core.sim

import com.coachpocket.core.protocol.PodPacketCodec
import com.coachpocket.core.protocol.PodProtocol
import com.coachpocket.core.protocol.RawPodPacket
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** One squat as the bar (and the wrist holding it) moves: down, pause, up, pause. */
data class SquatMotion(
    val eccentricS: Double = 2.0,
    val bottomPauseS: Double = 0.0,
    val concentricS: Double = 1.2,
    val topPauseS: Double = 2.0,
    val depthM: Double = 0.5,
) {
    val totalS: Double get() = eccentricS + bottomPauseS + concentricS + topPauseS

    /** Vertical acceleration (m/s^2, up positive) at [t] seconds into the repetition. */
    fun acceleration(t: Double): Double = when {
        t < eccentricS -> -depthM / 2 * (PI / eccentricS) * (PI / eccentricS) * cos(PI * t / eccentricS)
        t < eccentricS + bottomPauseS -> 0.0
        t < eccentricS + bottomPauseS + concentricS -> {
            val u = t - eccentricS - bottomPauseS
            depthM / 2 * (PI / concentricS) * (PI / concentricS) * cos(PI * u / concentricS)
        }
        else -> 0.0
    }
}

class SimulatedPacket(
    val bytes: ByteArray,
    /** True (phone clock) time of the last sample in the packet = earliest moment it can be sent. */
    val readyPhoneUs: Long,
)

/**
 * Stand-in for the wrist pod: produces the exact BLE packets the firmware will send, for a lifter
 * repeating [motions]. Used by the unit tests and by the app's "simulated pod" switch so the
 * receive path, loss counters and clock sync can be exercised before the hardware exists.
 */
class SimulatedPod(
    private val motions: List<SquatMotion>,
    private val rateHz: Int = 1000,
    private val leadInS: Double = 2.0,
    /** Direction of "up" in the pod frame (need not be normalised). */
    up: FloatArray = floatArrayOf(0.3f, -0.5f, 0.8f),
    /** Pod clock = phone clock * (1 + skew) + offset. */
    private val clockOffsetUs: Long = 123_456_789,
    private val clockSkewPpm: Double = 40.0,
    private val accelNoise: Double = 0.03,
    private val gyroNoise: Double = 0.003,
    private val startPhoneUs: Long = 0,
    seed: Long = 7,
) {
    private val rng = Random(seed)
    private val upUnit: FloatArray = run {
        val n = Math.sqrt((up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).toDouble()).toFloat()
        floatArrayOf(up[0] / n, up[1] / n, up[2] / n)
    }
    private val periodUs = 1_000_000 / rateHz
    private val accelFs = 3 // +-16 g
    private val gyroFs = 3 // +-2000 dps
    private val cycleS = motions.sumOf { it.totalS }
    private var sampleIndex = 0L
    private var seq = 0

    fun podClockUs(phoneUs: Long): Long = Math.round(phoneUs * (1 + clockSkewPpm * 1e-6)) + clockOffsetUs

    private fun verticalAcceleration(tS: Double): Double {
        if (tS < leadInS || cycleS <= 0) return 0.0
        var t = (tS - leadInS) % cycleS
        for (m in motions) {
            if (t < m.totalS) return m.acceleration(t)
            t -= m.totalS
        }
        return 0.0
    }

    fun nextPacket(samples: Int = PodProtocol.MAX_SAMPLES_PER_PACKET): SimulatedPacket {
        val raw = ShortArray(samples * 6)
        val firstPhoneUs = startPhoneUs + sampleIndex * periodUs
        val aScale = PodProtocol.accelScale(accelFs)
        val gScale = PodProtocol.gyroScale(gyroFs)
        for (i in 0 until samples) {
            val tS = (sampleIndex + i) * periodUs / 1e6
            val specific = verticalAcceleration(tS) + PodProtocol.STANDARD_GRAVITY
            for (k in 0 until 3) {
                val a = specific * upUnit[k] + rng.nextGaussian() * accelNoise
                raw[i * 6 + k] = (a / aScale).roundToInt().coerceIn(-32768, 32767).toShort()
                val g = rng.nextGaussian() * gyroNoise
                raw[i * 6 + 3 + k] = (g / gScale).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        val packet = RawPodPacket(seq, podClockUs(firstPhoneUs) and 0xFFFFFFFFL, periodUs, accelFs, gyroFs, raw)
        seq = (seq + 1) and 0xFFFF
        sampleIndex += samples
        return SimulatedPacket(PodPacketCodec.encode(packet), firstPhoneUs + (samples - 1).toLong() * periodUs)
    }
}
