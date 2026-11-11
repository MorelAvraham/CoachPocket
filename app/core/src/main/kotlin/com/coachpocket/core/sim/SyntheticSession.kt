package com.coachpocket.core.sim

import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.protocol.PodStreamDecoder
import com.coachpocket.core.session.ImuRow
import com.coachpocket.core.session.PoseRow
import com.coachpocket.core.session.RecordedSession
import com.coachpocket.core.session.SessionMeta
import com.coachpocket.core.sync.ClockSync
import java.util.Random

/** One simulated set. */
data class SyntheticSetSpec(
    val subject: String,
    val condition: Set<FaultType>,
    val reps: Int = 5,
    val seed: Long = 1,
    val withPod: Boolean = true,
    val frontal: Boolean = true,
    /** Probability that a BLE packet is lost. */
    val packetLoss: Double = 0.0,
    val keypointNoise: Float = 0.003f,
)

/**
 * Generates a recorded session in the real file format from the simulated pod and the stick-figure
 * lifter, passing the pod data through the same decoder and clock sync the phone uses.
 *
 * Purpose: to exercise the recording, replay and evaluation pipeline before real data exists.
 * Scores obtained on these sessions say nothing about performance on people.
 */
object SyntheticSession {
    private const val LEAD_IN_S = 2.0

    fun generate(spec: SyntheticSetSpec): RecordedSession {
        val rng = Random(spec.seed)
        fun uniform(lo: Double, hi: Double) = lo + rng.nextDouble() * (hi - lo)

        val fast = FaultType.TOO_FAST_DESCENT in spec.condition
        val caving = FaultType.KNEE_VALGUS in spec.condition
        val motions = List(spec.reps) {
            SquatMotion(
                eccentricS = if (fast) uniform(0.45, 0.75) else uniform(1.6, 2.6),
                concentricS = uniform(1.0, 1.5),
                topPauseS = uniform(1.5, 2.5),
                depthM = uniform(0.42, 0.55),
            )
        }
        val valgusPeak = List(spec.reps) { if (caving) uniform(0.11, 0.18).toFloat() else uniform(-0.04, 0.02).toFloat() }
        val repStart = DoubleArray(spec.reps)
        var acc = LEAD_IN_S
        for (i in motions.indices) {
            repStart[i] = acc; acc += motions[i].totalS
        }
        val endUs = (acc * 1e6).toLong() // stop before SimulatedPod would start the list again

        val poses = ArrayList<PoseRow>()
        var t = 0L
        while (t < endUs) {
            val tS = t / 1e6
            var rep = -1
            for (i in motions.indices) if (tS >= repStart[i]) rep = i
            var h = 1f
            var valgus = 0f
            if (rep >= 0) {
                val m = motions[rep]
                val u = tS - repStart[rep]
                h = SyntheticLifter.height(m, u, bottomH = 1 - m.depthM)
                // Knees cave from 60% of the way down until 40% of the way up.
                val inFaultWindow = u in (0.6 * m.eccentricS)..(m.eccentricS + m.bottomPauseS + 0.4 * m.concentricS)
                valgus = if (caving && !inFaultWindow) 0f else valgusPeak[rep]
            }
            val frame = SyntheticLifter.frame(t, h, valgus, spec.frontal, noise = spec.keypointNoise, rng = rng)
            poses += PoseRow(frame, t + uniform(50_000.0, 80_000.0).toLong())
            t += 33_333
        }

        val imu = ArrayList<ImuRow>()
        if (spec.withPod) {
            val pod = SimulatedPod(motions, leadInS = LEAD_IN_S, seed = spec.seed + 1)
            val decoder = PodStreamDecoder()
            val sync = ClockSync()
            var lastRx = 0L
            while (true) {
                val packet = pod.nextPacket()
                if (packet.readyPhoneUs > endUs) break
                if (rng.nextDouble() < spec.packetLoss) continue
                // BLE delivers in order: a packet cannot overtake the one before it.
                val rx = maxOf(lastRx + 1, packet.readyPhoneUs + 6_000 + uniform(0.0, 12_000.0).toLong())
                lastRx = rx
                val decoded = decoder.decode(packet.bytes) ?: continue
                sync.addOneWay(decoded.lastSamplePodUs, rx)
                for (s in decoded.samples) imu += ImuRow(s.copy(tUs = sync.podToPhoneUs(s.tUs)), s.tUs, rx)
            }
        }

        val meta = SessionMeta.of(
            subject = spec.subject, condition = spec.condition, expectedReps = spec.reps,
            extra = mapOf(
                "view" to if (spec.frontal) "front" else "side",
                "pod" to if (spec.withPod) "simulated" else "none",
                "synthetic" to "true",
            ),
        )
        val name = "${spec.subject}_${meta.values["condition"]}_${spec.seed}"
        return RecordedSession(name, meta, imu, poses)
    }
}
