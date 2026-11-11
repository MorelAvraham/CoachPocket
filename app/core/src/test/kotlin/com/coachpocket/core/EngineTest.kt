package com.coachpocket.core

import com.coachpocket.core.cue.CueDecision
import com.coachpocket.core.cue.CueManager
import com.coachpocket.core.engine.Channel
import com.coachpocket.core.engine.CoachEngine
import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.imu.ImuDescentAnalyzer
import com.coachpocket.core.imu.TempoEvent
import com.coachpocket.core.protocol.PodStreamDecoder
import com.coachpocket.core.sim.SimulatedPod
import com.coachpocket.core.sim.SquatMotion
import com.coachpocket.core.sim.SyntheticLifter
import com.coachpocket.core.sync.ClockSync
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineTest {
    private val leadIn = 2.0
    private val cameraDelayUs = 60_000L // capture-to-result latency of the pose pipeline
    private val bleDelayUs = 8_000L

    private class Run(val events: List<CoachEvent>, val engine: CoachEngine, val sync: ClockSync, val decoder: PodStreamDecoder)

    /**
     * Full pipeline in simulated time: simulated pod -> BLE bytes -> decoder -> clock sync -> engine,
     * interleaved with synthetic pose frames in arrival order.
     */
    private fun simulate(
        motions: List<SquatMotion>,
        seconds: Double,
        withPod: Boolean = true,
        withVision: Boolean = true,
        lifterStandsStill: Boolean = false,
        valgus: (Double) -> Float = { 0f },
    ): Run {
        var now = 0L
        val engine = CoachEngine(nowUs = { now })
        val pod = SimulatedPod(motions, leadInS = leadIn)
        val decoder = PodStreamDecoder()
        val sync = ClockSync()
        val events = ArrayList<CoachEvent>()

        var nextFrameCaptureUs = 0L
        var packet = pod.nextPacket()
        val endUs = (seconds * 1e6).toLong()
        while (true) {
            val packetArrival = if (withPod) packet.readyPhoneUs + bleDelayUs else Long.MAX_VALUE
            val frameArrival = if (withVision) nextFrameCaptureUs + cameraDelayUs else Long.MAX_VALUE
            val next = minOf(packetArrival, frameArrival)
            if (next > endUs) break
            now = next
            if (packetArrival <= frameArrival) {
                val decoded = decoder.decode(packet.bytes)!!
                sync.addOneWay(decoded.lastSamplePodUs, now)
                events += engine.onImuSamples(decoded.samples.map { it.copy(tUs = sync.podToPhoneUs(it.tUs)) })
                packet = pod.nextPacket()
            } else {
                val tS = nextFrameCaptureUs / 1e6
                val h = if (lifterStandsStill) 1f else SyntheticLifter.heightAt(motions, leadIn, tS)
                events += engine.onPoseFrame(SyntheticLifter.frame(nextFrameCaptureUs, h, valgus(tS)))
                nextFrameCaptureUs += 33_333
            }
        }
        return Run(events, engine, sync, decoder)
    }

    @Test
    fun cleanSetProducesRepsAndNoCues() {
        val run = simulate(listOf(SquatMotion(eccentricS = 2.0, concentricS = 1.2, topPauseS = 1.5)), seconds = 16.5)
        assertEquals(3, run.engine.repCount)
        assertTrue(run.events.none { it is CoachEvent.FaultDetected || it is CoachEvent.CueIssued }, run.events.toString())
        // The pod's own measurement of the eccentric phase agrees with the truth.
        val d = run.engine.lastDescent!!
        assertTrue(abs(d.durationUs / 1e6 - 2.0) < 0.4, "duration ${d.durationUs}")
        assertTrue(abs(d.travelM - 0.5f) < 0.08f, "travel ${d.travelM}")
        assertEquals(0L, run.decoder.packetsLost)
    }

    @Test
    fun diveBombIsCuedByThePodBeforeTheBottomOfTheSameRep() {
        val motion = SquatMotion(eccentricS = 0.6, concentricS = 1.0)
        val run = simulate(listOf(motion), seconds = 5.0)
        val cue = run.events.filterIsInstance<CoachEvent.CueIssued>().single()
        assertEquals(FaultType.TOO_FAST_DESCENT, cue.cue.fault)
        assertEquals(Channel.FUSED, cue.fault.channel)
        val bottomUs = ((leadIn + motion.eccentricS) * 1e6).toLong()
        assertTrue(cue.tDecisionUs < bottomUs, "cue decided at ${cue.tDecisionUs}, bottom at $bottomUs")
        val latencyMs = (cue.tDecisionUs - cue.fault.tOnsetUs) / 1000
        assertTrue(latencyMs in 0..250, "onset-to-decision $latencyMs ms")
        // Only one cue for the fault, and the vision fallback stays quiet while the pod streams.
        assertEquals(1, run.events.count { it is CoachEvent.FaultDetected })
    }

    @Test
    fun podMotionOutsideARepIsVetoedByVision() {
        // The wrist drops fast (re-racking, shaking out the arm) while the camera sees the lifter standing.
        val run = simulate(listOf(SquatMotion(eccentricS = 0.6, concentricS = 1.0)), seconds = 5.0, lifterStandsStill = true)
        assertTrue(run.events.none { it is CoachEvent.CueIssued })
        assertTrue(run.events.any { it is CoachEvent.FaultVetoed })
    }

    @Test
    fun podAloneStillCuesWhenTheCameraSeesNobody() {
        val run = simulate(listOf(SquatMotion(eccentricS = 0.6, concentricS = 1.0)), seconds = 5.0, withVision = false)
        val cue = run.events.filterIsInstance<CoachEvent.CueIssued>().single()
        assertEquals(Channel.IMU, cue.fault.channel)
    }

    @Test
    fun cameraAloneFallsBackToVisionTempo() {
        val run = simulate(listOf(SquatMotion(eccentricS = 0.6, concentricS = 1.0)), seconds = 5.0, withPod = false)
        val cue = run.events.filterIsInstance<CoachEvent.CueIssued>().single()
        assertEquals(Channel.VISION, cue.fault.channel)
    }

    @Test
    fun valgusCueMeetsTheLatencyBudgetInSimulation() {
        val run = simulate(listOf(SquatMotion(eccentricS = 2.0, concentricS = 1.2)), seconds = 6.0, valgus = { t -> if (t in 3.2..4.6) 0.14f else 0f })
        val cue = run.events.filterIsInstance<CoachEvent.CueIssued>().single()
        assertEquals(FaultType.KNEE_VALGUS, cue.cue.fault)
        val latencyMs = (cue.tDecisionUs - cue.fault.tOnsetUs) / 1000
        // 2 more frames to confirm (67 ms) + 60 ms pipeline delay.
        assertTrue(latencyMs in 100..160, "onset-to-decision $latencyMs ms")
    }

    @Test
    fun clockSyncLocksOnTheSimulatedStream() {
        val run = simulate(listOf(SquatMotion()), seconds = 20.0)
        assertTrue(run.sync.isLocked)
        assertEquals(-40.0, run.sync.skewPpm, 5.0)
    }

    @Test
    fun analyzerFlagsFastButNotSlowDescents() {
        fun tempo(eccentric: Double): List<TempoEvent> {
            val pod = SimulatedPod(listOf(SquatMotion(eccentricS = eccentric)), clockOffsetUs = 0, clockSkewPpm = 0.0)
            val dec = PodStreamDecoder()
            val analyzer = ImuDescentAnalyzer()
            val out = ArrayList<TempoEvent>()
            repeat(300) { dec.decode(pod.nextPacket().bytes)!!.samples.forEach { s -> analyzer.process(s, out) } }
            return out
        }
        val fast = tempo(0.6)
        val early = fast.filterIsInstance<TempoEvent.TooFast>().single()
        assertTrue(early.tConfirmUs < 2_300_000, "flagged at ${early.tConfirmUs}") // descent runs 2.0-2.6 s
        assertTrue(fast.filterIsInstance<TempoEvent.DescentEnded>().first().flaggedEarly)
        assertTrue(tempo(1.5).none { it is TempoEvent.TooFast })
    }

    @Test
    fun cueManagerSuppressesRepeatsAndLetsPriorityCutIn() {
        val cues = CueManager()
        fun fault(type: FaultType) = CoachEvent.FaultDetected(type, Channel.VISION, 0, 0, 1f, "")
        assertIs<CueDecision.Speak>(cues.decide(fault(FaultType.TOO_FAST_DESCENT), 0))
        // Same cue 3 s later: cooldown.
        assertIs<CueDecision.Suppress>(cues.decide(fault(FaultType.TOO_FAST_DESCENT), 3_000_000))
        // Lower priority while a cue is playing: dropped. Higher priority: interrupts.
        val t = 10_000_000L
        assertIs<CueDecision.Speak>(cues.decide(fault(FaultType.KNEE_VALGUS), t))
        assertIs<CueDecision.Suppress>(cues.decide(fault(FaultType.TOO_FAST_DESCENT), t + 500_000))
        cues.reset()
        assertIs<CueDecision.Speak>(cues.decide(fault(FaultType.TOO_FAST_DESCENT), t))
        val cut = assertIs<CueDecision.Speak>(cues.decide(fault(FaultType.KNEE_VALGUS), t + 500_000))
        assertTrue(cut.cue.interrupt)
        assertEquals(2, cues.spoken)
    }
}
