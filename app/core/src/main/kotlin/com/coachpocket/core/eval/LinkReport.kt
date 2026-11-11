package com.coachpocket.core.eval

import com.coachpocket.core.session.RecordedSession
import com.coachpocket.core.util.fmt

/**
 * BLE link statistics from a recorded IMU stream: the numbers test U1 asks for (requirement R1).
 * Lost samples are found as gaps in the pod's own timestamps, so the result does not depend on
 * anything the phone did after reception.
 */
class LinkReport(
    val samples: Int,
    val durationS: Double,
    val periodUs: Long,
    val lostSamples: Long,
    val gaps: Int,
    val longestGapMs: Double,
    val packets: Int,
    val packetIntervalP50Ms: Double,
    val packetIntervalP95Ms: Double,
    val packetIntervalMaxMs: Double,
    /** Spread of (arrival - mapped sample time) for the last sample of each packet: transport jitter. */
    val delayP50Ms: Double,
    val delayP95Ms: Double,
    val delayMaxMs: Double,
) {
    val effectiveRateHz: Double get() = if (durationS > 0) samples / durationS else 0.0
    val nominalRateHz: Double get() = if (periodUs > 0) 1e6 / periodUs else 0.0
    val lossPercent: Double get() = if (samples + lostSamples == 0L) 0.0 else 100.0 * lostSamples / (samples + lostSamples)

    fun text(): String = buildString {
        append(fmt("samples received     %d over %.1f s\n", samples, durationS))
        append(fmt("sample rate          %.1f Hz effective, %.1f Hz nominal\n", effectiveRateHz, nominalRateHz))
        append(fmt("lost samples         %d (%.3f %%) in %d gaps, longest %.1f ms\n", lostSamples, lossPercent, gaps, longestGapMs))
        append(fmt("packets              %d, interval p50 %.1f / p95 %.1f / max %.1f ms\n", packets, packetIntervalP50Ms, packetIntervalP95Ms, packetIntervalMaxMs))
        append(fmt("transport delay      p50 %.1f / p95 %.1f / max %.1f ms (relative to the clock-sync estimate)\n", delayP50Ms, delayP95Ms, delayMaxMs))
    }

    companion object {
        fun of(session: RecordedSession): LinkReport? {
            val imu = session.imu
            if (imu.size < 3) return null
            // Sample period = the most common pod-clock step.
            val steps = HashMap<Long, Int>()
            for (i in 1 until imu.size) steps.merge(imu[i].podUs - imu[i - 1].podUs, 1, Int::plus)
            val period = steps.maxBy { it.value }.key.coerceAtLeast(1)

            var lost = 0L
            var gaps = 0
            var longest = 0L
            for (i in 1 until imu.size) {
                val step = imu[i].podUs - imu[i - 1].podUs
                if (step > period * 3 / 2) {
                    gaps++
                    lost += Math.round(step.toDouble() / period) - 1
                    if (step > longest) longest = step
                }
            }

            val intervals = ArrayList<Double>()
            val delays = ArrayList<Double>()
            var packets = 0
            var lastRx = Long.MIN_VALUE
            for (i in imu.indices) {
                val last = i == imu.lastIndex || imu[i + 1].rxUs != imu[i].rxUs
                if (!last) continue
                packets++
                if (lastRx != Long.MIN_VALUE) intervals += (imu[i].rxUs - lastRx) / 1000.0
                lastRx = imu[i].rxUs
                delays += (imu[i].rxUs - imu[i].sample.tUs) / 1000.0
            }
            intervals.sort(); delays.sort()
            fun pct(v: List<Double>, p: Double) = if (v.isEmpty()) Double.NaN else v[Math.round(p / 100 * (v.size - 1)).toInt()]
            return LinkReport(
                samples = imu.size,
                durationS = (imu.last().podUs - imu.first().podUs) / 1e6,
                periodUs = period, lostSamples = lost, gaps = gaps, longestGapMs = longest / 1000.0,
                packets = packets,
                packetIntervalP50Ms = pct(intervals, 50.0), packetIntervalP95Ms = pct(intervals, 95.0),
                packetIntervalMaxMs = intervals.lastOrNull() ?: Double.NaN,
                delayP50Ms = pct(delays, 50.0), delayP95Ms = pct(delays, 95.0), delayMaxMs = delays.lastOrNull() ?: Double.NaN,
            )
        }
    }
}
