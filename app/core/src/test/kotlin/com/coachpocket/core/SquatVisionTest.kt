package com.coachpocket.core

import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.sim.SquatMotion
import com.coachpocket.core.sim.SyntheticLifter
import com.coachpocket.core.squat.SquatPhase
import com.coachpocket.core.squat.SquatVisionTracker
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SquatVisionTest {
    private val frameUs = 33_333L

    private fun run(
        motion: SquatMotion,
        reps: Int,
        frontal: Boolean = true,
        valgus: (repIndex: Int, tInRep: Double) -> Float = { _, _ -> 0f },
    ): Pair<SquatVisionTracker, List<CoachEvent>> {
        val tracker = SquatVisionTracker()
        val events = ArrayList<CoachEvent>()
        val lead = 1.5
        val total = lead + reps * motion.totalS
        var t = 0L
        while (t / 1e6 < total) {
            val s = t / 1e6
            val rep = if (s < lead) -1 else ((s - lead) / motion.totalS).toInt()
            val tInRep = if (rep < 0) -1.0 else (s - lead) - rep * motion.totalS
            val h = if (rep < 0) 1f else SyntheticLifter.height(motion, tInRep)
            events += tracker.process(SyntheticLifter.frame(t, h, if (rep < 0) 0f else valgus(rep, tInRep), frontal)).events
            t += frameUs
        }
        return tracker to events
    }

    @Test
    fun countsCleanRepsAndMeasuresTempo() {
        val (tracker, events) = run(SquatMotion(eccentricS = 2.0, concentricS = 1.2), reps = 3)
        assertEquals(3, tracker.repCount)
        assertEquals(SquatPhase.TOP, tracker.phase)
        val reps = events.filterIsInstance<CoachEvent.RepCompleted>()
        assertEquals(listOf(1, 2, 3), reps.map { it.index })
        for (r in reps) {
            assertTrue(abs(r.eccentricUs / 1e6 - 2.0) < 0.35, "eccentric ${r.eccentricUs}")
            assertTrue(abs(r.depth - 0.5f) < 0.03f, "depth ${r.depth}")
        }
        assertTrue(events.none { it is CoachEvent.FaultDetected })
    }

    @Test
    fun shallowDipIsNotARep() {
        val tracker = SquatVisionTracker()
        var t = 0L
        repeat(45) { tracker.process(SyntheticLifter.frame(t, 1f)); t += frameUs }
        for (h in listOf(0.95f, 0.9f, 0.86f, 0.86f, 0.9f, 0.95f, 1f, 1f, 1f, 1f, 1f, 1f)) {
            repeat(4) { tracker.process(SyntheticLifter.frame(t, h)); t += frameUs }
        }
        assertEquals(0, tracker.repCount)
        assertEquals(SquatPhase.TOP, tracker.phase)
    }

    @Test
    fun kneeValgusIsFlaggedOncePerRepSoonAfterOnset() {
        val motion = SquatMotion(eccentricS = 2.0, concentricS = 1.2)
        // Knees cave from 1.2 s into each of two repetitions until 2.6 s.
        val (_, events) = run(motion, reps = 2, valgus = { _, t -> if (t in 1.2..2.6) 0.14f else -0.03f })
        val faults = events.filterIsInstance<CoachEvent.FaultDetected>()
        assertEquals(2, faults.size)
        for ((i, f) in faults.withIndex()) {
            assertEquals(FaultType.KNEE_VALGUS, f.fault)
            val onsetInRep = f.tOnsetUs / 1e6 - 1.5 - i * motion.totalS
            assertTrue(abs(onsetInRep - 1.2) < 0.05, "onset at $onsetInRep")
            assertTrue(f.tConfirmUs - f.tOnsetUs <= 2 * frameUs + 1, "confirmed within 3 frames")
            assertTrue(f.detail.startsWith("both knees"))
        }
    }

    @Test
    fun singleNoisyFrameDoesNotTriggerValgus() {
        var n = 0
        val (_, events) = run(SquatMotion(), reps = 2, valgus = { _, t -> if (t in 1.0..2.5 && n++ % 5 == 0) 0.2f else 0f })
        assertTrue(events.none { it is CoachEvent.FaultDetected })
    }

    @Test
    fun valgusIsNotJudgedFromTheSide() {
        val (tracker, events) = run(SquatMotion(), reps = 2, frontal = false, valgus = { _, _ -> 0.3f })
        assertEquals(2, tracker.repCount)
        assertTrue(events.none { it is CoachEvent.FaultDetected })
    }

    @Test
    fun visionAloneReportsADiveBomb() {
        val (_, events) = run(SquatMotion(eccentricS = 0.6, concentricS = 1.0), reps = 1)
        val fault = events.filterIsInstance<CoachEvent.FaultDetected>().single()
        assertEquals(FaultType.TOO_FAST_DESCENT, fault.fault)
    }

    @Test
    fun losingTheLifterResetsTracking() {
        val tracker = SquatVisionTracker()
        var t = 0L
        repeat(40) { tracker.process(SyntheticLifter.frame(t, 1f)); t += frameUs }
        assertEquals(SquatPhase.TOP, tracker.phase)
        repeat(40) { tracker.process(SyntheticLifter.frame(t, 1f, visible = false)); t += frameUs }
        assertEquals(SquatPhase.NOT_TRACKED, tracker.phase)
    }
}
