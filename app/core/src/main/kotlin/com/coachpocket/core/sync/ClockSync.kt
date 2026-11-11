package com.coachpocket.core.sync

/**
 * Maps pod timestamps onto the phone clock (requirement R2).
 *
 * One-way part. Every data packet gives a pair (pod time of its last sample, phone receive time).
 * Receive time = pod time + offset + transport delay, and the delay is never negative, so the
 * smallest values of (rx - pod) trace the true offset plus the minimum transport delay. We keep the
 * minimum per time bucket and fit a line through those minima: intercept = offset, slope = skew.
 * This needs nothing from the firmware beyond honest local timestamps.
 *
 * Two-way part. The one-way estimate is late by the minimum transport delay, which it cannot see.
 * [addTwoWay] measures it with an NTP-style beacon and stores it in [transportBiasUs]. Without the
 * beacon the bias can be set from the tap test (U2).
 */
class ClockSync(
    private val bucketUs: Long = 500_000,
    private val maxBuckets: Int = 120,
    private val minBucketsForSkew: Int = 10,
) {
    private class Bucket(val key: Long, var podUs: Long, var deltaUs: Long)

    private val buckets = ArrayDeque<Bucket>()
    private var lastPodUs = Long.MIN_VALUE

    // Fitted line: delta(pod) = meanDelta + slope * (pod - meanPod)
    private var meanPod = 0.0
    private var meanDelta = 0.0
    private var slope = 0.0
    private var fitted = false

    private class TwoWay(val rttUs: Long, val biasUs: Long)

    private val twoWay = ArrayDeque<TwoWay>()

    /** Minimum transport delay to subtract from the one-way estimate, microseconds. */
    @Volatile
    var transportBiasUs: Long = 0

    val isLocked: Boolean get() = fitted

    /** Pod clock rate error relative to the phone, parts per million. */
    val skewPpm: Double get() = slope * 1e6

    /** Current phone-minus-pod offset, microseconds. */
    val offsetUs: Long get() = if (fitted) delta(lastPodUs) - transportBiasUs else 0

    val lastRttUs: Long get() = twoWay.lastOrNull()?.rttUs ?: -1

    @Synchronized
    fun reset() {
        buckets.clear(); twoWay.clear()
        transportBiasUs = 0 // measured on the previous link; meaningless for the next one
        lastPodUs = Long.MIN_VALUE
        fitted = false; slope = 0.0
    }

    @Synchronized
    fun addOneWay(podUs: Long, rxPhoneUs: Long) {
        if (lastPodUs != Long.MIN_VALUE && podUs < lastPodUs - 1_000_000) reset() // pod rebooted
        if (podUs > lastPodUs) lastPodUs = podUs
        val d = rxPhoneUs - podUs
        val key = Math.floorDiv(podUs, bucketUs)
        val last = buckets.lastOrNull()
        if (last != null && last.key == key) {
            if (d < last.deltaUs) {
                last.deltaUs = d; last.podUs = podUs
            }
            if (!fitted) refit()
            return
        }
        buckets.addLast(Bucket(key, podUs, d))
        while (buckets.size > maxBuckets) buckets.removeFirst()
        refit()
    }

    /**
     * NTP-style exchange: phone sent at [t1PhoneUs], pod received at [t2PodUs] and replied at
     * [t3PodUs], phone received at [t4PhoneUs]. Pod times must be unwrapped.
     */
    @Synchronized
    fun addTwoWay(t1PhoneUs: Long, t2PodUs: Long, t3PodUs: Long, t4PhoneUs: Long) {
        if (!fitted) return
        val rtt = (t4PhoneUs - t1PhoneUs) - (t3PodUs - t2PodUs)
        if (rtt < 0) return
        val thetaUs = ((t2PodUs - t1PhoneUs) + (t3PodUs - t4PhoneUs)) / 2 // pod minus phone
        // One-way maps t2 to t2 + delta(t2); the beacon says the truth is t2 - theta.
        val bias = delta(t2PodUs) + thetaUs
        twoWay.addLast(TwoWay(rtt, bias))
        while (twoWay.size > 16) twoWay.removeFirst()
        // The exchange with the shortest round trip has the least asymmetric delay.
        transportBiasUs = twoWay.minBy { it.rttUs }.biasUs.coerceAtLeast(0)
    }

    @Synchronized
    fun podToPhoneUs(podUs: Long): Long = if (fitted) podUs + delta(podUs) - transportBiasUs else podUs

    private fun delta(podUs: Long): Long = Math.round(meanDelta + slope * (podUs - meanPod))

    private fun refit() {
        val n = buckets.size
        if (n == 0) return
        if (n < minBucketsForSkew) {
            // Not enough history for a slope yet: constant offset from the best sample so far.
            val best = buckets.minBy { it.deltaUs }
            meanPod = best.podUs.toDouble(); meanDelta = best.deltaUs.toDouble(); slope = 0.0
            fitted = true
            return
        }
        // The newest bucket is still filling and its minimum is biased high, so leave it out.
        val use = n - 1
        var sp = 0.0
        var sd = 0.0
        val p0 = buckets.first().podUs
        val d0 = buckets.first().deltaUs
        var i = 0
        for (b in buckets) {
            if (i++ >= use) break
            sp += (b.podUs - p0); sd += (b.deltaUs - d0)
        }
        val mp = sp / use
        val md = sd / use
        var spp = 0.0
        var spd = 0.0
        i = 0
        for (b in buckets) {
            if (i++ >= use) break
            val dp = (b.podUs - p0) - mp
            spp += dp * dp; spd += dp * ((b.deltaUs - d0) - md)
        }
        slope = if (spp > 0) spd / spp else 0.0
        meanPod = p0 + mp; meanDelta = d0 + md
        fitted = true
    }
}
