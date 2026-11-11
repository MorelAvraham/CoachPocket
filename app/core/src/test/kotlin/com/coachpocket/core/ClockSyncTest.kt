package com.coachpocket.core

import com.coachpocket.core.sync.ClockSync
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockSyncTest {
    private val offsetUs = 5_000_000_000L // phone = pod / (1 + skew) + offset
    private val skewPpm = 35.0
    private val minDelayUs = 4_000L

    private fun phoneOf(podUs: Long) = Math.round(podUs / (1 + skewPpm * 1e-6)) + offsetUs

    /** 60 s of packets every 19 ms with BLE-like delay: fixed minimum plus up to 15 ms of jitter. */
    private fun feed(sync: ClockSync, seconds: Int = 60): Long {
        val rng = Random(1)
        var pod = 1_000_000L
        repeat(seconds * 1000 / 19) {
            pod += 19_000
            sync.addOneWay(pod, phoneOf(pod) + minDelayUs + rng.nextInt(15_000))
        }
        return pod
    }

    @Test
    fun oneWayRecoversOffsetUpToTheMinimumDelay() {
        val sync = ClockSync()
        val pod = feed(sync)
        assertTrue(sync.isLocked)
        val error = sync.podToPhoneUs(pod) - phoneOf(pod)
        // One-way cannot see the minimum transport delay; everything else should be gone.
        assertTrue(abs(error - minDelayUs) < 1_500, "error was $error us")
        // Skew sign: the pod runs fast, so phone-minus-pod shrinks as pod time grows.
        assertEquals(-skewPpm, sync.skewPpm, 15.0)
    }

    @Test
    fun twoWayBeaconRemovesTheTransportBias() {
        val sync = ClockSync()
        val pod = feed(sync)
        val rng = Random(2)
        repeat(10) { i ->
            val t2 = pod + i * 1_000_000L
            val up = minDelayUs + rng.nextInt(8_000)
            val down = minDelayUs + rng.nextInt(8_000)
            sync.addTwoWay(phoneOf(t2) - up, t2, t2 + 300, phoneOf(t2 + 300) + down)
        }
        val error = sync.podToPhoneUs(pod) - phoneOf(pod)
        assertTrue(abs(error) < 3_000, "error was $error us") // well inside the 10 ms target of R2
    }

    @Test
    fun podRebootRestartsTheEstimate() {
        val sync = ClockSync()
        feed(sync, 10)
        sync.addOneWay(100, 9_000_000_000L) // pod clock jumped back to zero
        assertEquals(9_000_000_000L - 100 + 5_000, sync.podToPhoneUs(5_000))
    }

    @Test
    fun resetForgetsTheTransportBiasOfThePreviousLink() {
        val sync = ClockSync()
        val pod = feed(sync)
        sync.addTwoWay(phoneOf(pod) - 5_000, pod, pod + 300, phoneOf(pod + 300) + 5_000)
        assertTrue(sync.transportBiasUs > 0)
        sync.reset()
        assertEquals(0L, sync.transportBiasUs)
    }
}
