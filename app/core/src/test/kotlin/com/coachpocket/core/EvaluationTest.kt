package com.coachpocket.core

import com.coachpocket.core.engine.ChannelMode
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.eval.Evaluator
import com.coachpocket.core.eval.LinkReport
import com.coachpocket.core.eval.Replay
import com.coachpocket.core.eval.TapSyncReport
import com.coachpocket.core.imu.ImuSample
import com.coachpocket.core.pose.Landmark
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.pose.PoseIndex
import com.coachpocket.core.session.ImuRow
import com.coachpocket.core.session.PoseRow
import com.coachpocket.core.session.RecordedSession
import com.coachpocket.core.session.SessionFormat
import com.coachpocket.core.session.SessionMeta
import com.coachpocket.core.session.SessionReader
import com.coachpocket.core.session.SessionWriter
import com.coachpocket.core.sim.SyntheticSession
import com.coachpocket.core.sim.SyntheticSetSpec
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvaluationTest {
    private fun tempDir(): File = Files.createTempDirectory("coachpocket").toFile().also { it.deleteOnExit() }

    private val clean = SyntheticSession.generate(SyntheticSetSpec("S1", emptySet(), reps = 5, seed = 11))
    private val valgus = SyntheticSession.generate(SyntheticSetSpec("S1", setOf(FaultType.KNEE_VALGUS), reps = 5, seed = 12))
    private val fast = SyntheticSession.generate(SyntheticSetSpec("S2", setOf(FaultType.TOO_FAST_DESCENT), reps = 5, seed = 13))

    @Test
    fun faultNamesRoundTrip() {
        assertEquals("clean", SessionFormat.encodeFaults(emptySet()))
        for (set in listOf(emptySet(), setOf(FaultType.KNEE_VALGUS), FaultType.entries.toSet())) {
            assertEquals(set, SessionFormat.decodeFaults(SessionFormat.encodeFaults(set)))
        }
    }

    @Test
    fun sessionSurvivesWritingAndReading() {
        val dir = File(tempDir(), "set1")
        val labelled = RecordedSession(valgus.name, valgus.meta.with("subject" to "דנה"), valgus.imu, valgus.poses, mapOf(2 to emptySet()))
        SessionWriter.write(dir, labelled)
        assertEquals(listOf(dir), SessionReader.find(dir.parentFile))
        val back = SessionReader.read(dir)
        assertEquals("דנה", back.meta.subject)
        assertEquals(setOf(FaultType.KNEE_VALGUS), back.meta.condition)
        assertEquals(5, back.meta.expectedReps)
        assertEquals(emptySet(), back.labelOf(2))
        assertEquals(setOf(FaultType.KNEE_VALGUS), back.labelOf(3))
        assertEquals(valgus.imu.size, back.imu.size)
        assertEquals(valgus.poses.size, back.poses.size)
        val a = valgus.imu[1234]
        val b = back.imu[1234]
        assertEquals(a.sample, b.sample)
        assertEquals(a.podUs, b.podUs)
        assertEquals(a.rxUs, b.rxUs)
        assertEquals(valgus.poses[40].frame.image[PoseIndex.L_KNEE], back.poses[40].frame.image[PoseIndex.L_KNEE])
        // The file replays to exactly the same events as the in-memory session.
        assertEquals(Replay.run(valgus).toString(), Replay.run(RecordedSession("x", back.meta, back.imu, back.poses)).toString())
    }

    @Test
    fun repetitionsAreSegmentedAndLabelled() {
        val windows = Evaluator.repWindows(valgus)
        assertEquals(5, windows.size)
        assertTrue(windows.zipWithNext().all { (a, b) -> a.endUs <= b.startUs })
        assertTrue(windows.all { it.truth == setOf(FaultType.KNEE_VALGUS) })
    }

    @Test
    fun ablationShowsWhatEachChannelCanSee() {
        val results = Evaluator.evaluateAll(listOf(clean, valgus, fast))
        val fused = results.getValue(ChannelMode.FUSED)
        assertEquals(15, fused.reps)
        assertEquals(5, fused.cleanReps)
        assertEquals(0, fused.repCountAbsError)
        for (f in FaultType.entries) {
            assertEquals(1.0, fused.perFault.getValue(f).f1, 1e-9, "fused F1 for $f")
        }
        assertEquals(0.0, fused.falseCuesPer10Clean)
        assertEquals(0, fused.cuesOutsideReps)

        // The pod cannot see knees; the camera can still time a descent, only later.
        val imuOnly = results.getValue(ChannelMode.IMU_ONLY)
        assertEquals(0.0, imuOnly.perFault.getValue(FaultType.KNEE_VALGUS).recall)
        assertEquals(1.0, imuOnly.perFault.getValue(FaultType.TOO_FAST_DESCENT).recall)
        val visionOnly = results.getValue(ChannelMode.VISION_ONLY)
        assertEquals(1.0, visionOnly.perFault.getValue(FaultType.KNEE_VALGUS).f1)
        assertEquals(1.0, visionOnly.perFault.getValue(FaultType.TOO_FAST_DESCENT).recall)

        val md = Evaluator.markdown("demo", results)
        assertTrue("| knee_valgus | imu_only |" in md && "False cues / 10 clean reps" in md)
    }

    @Test
    fun perRepLabelsOverrideTheSetCondition() {
        // Operator marks every repetition of the valgus set as clean: all detections become false positives.
        val relabelled = RecordedSession(valgus.name, valgus.meta, valgus.imu, valgus.poses, (1..5).associateWith { emptySet() })
        val r = Evaluator.evaluate(relabelled, ChannelMode.FUSED)
        assertEquals(5, r.perFault.getValue(FaultType.KNEE_VALGUS).fp)
        assertEquals(5, r.cleanReps)
        assertTrue(r.falseCuesPer10Clean > 0)
    }

    @Test
    fun linkReportFindsLostPackets() {
        val perfect = assertNotNull(LinkReport.of(clean))
        assertEquals(0L, perfect.lostSamples)
        assertEquals(1000.0, perfect.nominalRateHz, 0.1)
        assertEquals(1000.0, perfect.effectiveRateHz, 2.0)

        val lossy = SyntheticSession.generate(SyntheticSetSpec("S1", emptySet(), reps = 5, seed = 11, packetLoss = 0.02))
        val report = assertNotNull(LinkReport.of(lossy))
        assertTrue(report.lostSamples > 0 && report.lostSamples % 19 == 0L, "lost ${report.lostSamples}")
        assertTrue(report.lossPercent in 0.5..5.0, "loss ${report.lossPercent}")
        assertTrue("lost samples" in report.text())
    }

    @Test
    fun tapTestRecoversAKnownSyncOffset() {
        // Hand goes down 0.25 of the image in 150 ms, hits the table, lifts slowly; 8 taps, 1.5 s apart.
        // The pod stream is deliberately 20 ms late against the camera.
        val offsetUs = 20_000L
        val impacts = List(8) { 1_000_000L + it * 1_500_000L + it * 4_321L }
        fun wristY(tUs: Long): Float {
            val next = impacts.firstOrNull { it >= tUs }
            val prev = impacts.lastOrNull { it < tUs }
            return when {
                next != null && next - tUs <= 150_000 -> 0.7f - 0.25f * (next - tUs) / 150_000f
                prev != null && tUs - prev <= 600_000 -> 0.7f - 0.25f * (tUs - prev) / 600_000f
                else -> 0.45f
            }
        }
        val poses = ArrayList<PoseRow>()
        var t = 0L
        while (t < 14_000_000) {
            val lm = MutableList(PoseIndex.COUNT) { Landmark(0.5f, 0.5f, 0f, 1f) }
            lm[PoseIndex.R_WRIST] = Landmark(0.4f, wristY(t), 0f, 1f)
            poses += PoseRow(PoseFrame(t, lm, emptyList(), 0.75f), t + 60_000)
            t += 33_333
        }
        val imu = ArrayList<ImuRow>()
        var u = 0L
        while (u < 14_000_000) {
            val spike = impacts.any { u - offsetUs in it..(it + 5_000) }
            imu += ImuRow(ImuSample(u, 0f, 0f, if (spike) 60f else 9.81f, 0f, 0f, 0f), u, u + 8_000)
            u += 1_000
        }
        val report = TapSyncReport.of(RecordedSession("tap", SessionMeta.of("S1", emptySet()), imu, poses))
        assertEquals(8, report.imuTaps)
        assertEquals(8, report.errorsMs.size)
        // Individual taps are quantised to the frame grid; the mean recovers the offset to within half a frame.
        assertTrue(abs(report.meanMs - 20.0) < 17.0, "mean ${report.meanMs}")
        assertTrue(report.maxAbsMs < 60.0)
        assertTrue("requirement R2" in report.text())
    }
}
