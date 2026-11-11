package com.coachpocket.core.engine

import com.coachpocket.core.cue.CueConfig
import com.coachpocket.core.cue.CueDecision
import com.coachpocket.core.cue.CueManager
import com.coachpocket.core.engine.CoachEvent.FaultDetected
import com.coachpocket.core.imu.ImuDescentAnalyzer
import com.coachpocket.core.imu.ImuSample
import com.coachpocket.core.imu.TempoConfig
import com.coachpocket.core.imu.TempoEvent
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.squat.SquatConfig
import com.coachpocket.core.squat.SquatFrameResult
import com.coachpocket.core.squat.SquatPhase
import com.coachpocket.core.squat.SquatVisionTracker

data class EngineConfig(
    val squat: SquatConfig = SquatConfig(),
    val tempo: TempoConfig = TempoConfig(),
    val cue: CueConfig = CueConfig(),
    /** How long an IMU fault waits for the camera to confirm that a repetition is in progress. */
    val visionConfirmWindowUs: Long = 200_000,
    /** The pod counts as streaming if a sample arrived within this time. */
    val imuAliveUs: Long = 500_000,
)

/**
 * The squat coach: takes pose frames and IMU samples (both on the phone clock) and produces
 * repetitions, faults and cue decisions. Pure logic with no Android dependency, so recorded
 * sessions can be replayed through it on a laptop for evaluation and ablation.
 *
 * Fusion at this stage is rule-based and works in both directions:
 *  - vision tells the IMU integrator when the lifter is moving, which stops false zero-velocity
 *    resets in the middle of a slow descent;
 *  - an IMU "too fast" fault only becomes a cue if vision sees a repetition in progress, which
 *    rejects walking the bar out, re-racking and arm movement between sets.
 * A learned fusion classifier (M3) replaces the rules behind the same events.
 */
class CoachEngine(
    private val config: EngineConfig = EngineConfig(),
    private val nowUs: () -> Long,
) {
    private val vision = SquatVisionTracker(config.squat)
    private val imu = ImuDescentAnalyzer(config.tempo)
    private val cues = CueManager(config.cue)
    private val tempoEvents = ArrayList<TempoEvent>(2)

    private var lastImuUs = Long.MIN_VALUE
    private var lastPoseUs = Long.MIN_VALUE
    private var pendingImuFault: FaultDetected? = null
    private var imuFaultThisRep = false

    var lastResult: SquatFrameResult? = null; private set
    var lastDescent: TempoEvent.DescentEnded? = null; private set
    val repCount: Int get() = vision.repCount
    val phase: SquatPhase get() = vision.phase
    val cuesSpoken: Int get() = cues.spoken
    val cuesSuppressed: Int get() = cues.suppressed
    val imuVelocity: Float get() = imu.velocity

    @Synchronized
    fun reset() {
        vision.reset(); imu.reset(); cues.reset()
        lastImuUs = Long.MIN_VALUE; lastPoseUs = Long.MIN_VALUE
        pendingImuFault = null; imuFaultThisRep = false; lastResult = null; lastDescent = null
    }

    @Synchronized
    fun onPoseFrame(frame: PoseFrame): List<CoachEvent> {
        val out = ArrayList<CoachEvent>(4)
        lastPoseUs = frame.tUs
        // Tempo comes from the pod when it is streaming, from the camera otherwise.
        vision.visionTempoEnabled = !imuAlive(frame.tUs)
        val result = vision.process(frame)
        lastResult = result
        imu.zeroVelocityAllowed = result.phase != SquatPhase.DESCENT && result.phase != SquatPhase.ASCENT

        for (e in result.events) {
            out += e
            when (e) {
                is CoachEvent.RepStarted -> imuFaultThisRep = false
                is FaultDetected -> emitFault(e, out)
                else -> {}
            }
        }
        resolvePending(frame.tUs, out)
        return out
    }

    @Synchronized
    fun onImuSamples(samples: List<ImuSample>): List<CoachEvent> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<CoachEvent>(2)
        for (s in samples) {
            tempoEvents.clear()
            imu.process(s, tempoEvents)
            for (e in tempoEvents) onTempoEvent(e, out)
        }
        lastImuUs = samples.last().tUs
        resolvePending(lastImuUs, out)
        return out
    }

    private fun onTempoEvent(e: TempoEvent, out: MutableList<CoachEvent>) {
        when (e) {
            is TempoEvent.TooFast -> offerImuFault(
                FaultDetected(
                    FaultType.TOO_FAST_DESCENT, Channel.IMU, e.tOnsetUs, e.tConfirmUs,
                    severity = e.speed / config.tempo.maxDescentSpeed,
                    detail = "descent speed %.2f m/s".format(e.speed),
                ),
                out,
            )

            is TempoEvent.DescentEnded -> {
                lastDescent = e
                if (!e.flaggedEarly && e.durationUs < config.tempo.minEccentricUs) {
                    offerImuFault(
                        FaultDetected(
                            FaultType.TOO_FAST_DESCENT, Channel.IMU, e.tEndUs, e.tEndUs,
                            severity = config.tempo.minEccentricUs.toFloat() / e.durationUs.coerceAtLeast(1),
                            detail = "eccentric %.2f s, peak %.2f m/s".format(e.durationUs / 1e6, e.peakSpeed),
                        ),
                        out,
                    )
                }
                if (pendingImuFault == null) imuFaultThisRep = false // next descent starts clean
            }
        }
    }

    private fun offerImuFault(fault: FaultDetected, out: MutableList<CoachEvent>) {
        if (imuFaultThisRep) return
        val visionTracking = lastPoseUs != Long.MIN_VALUE && fault.tConfirmUs - lastPoseUs < config.imuAliveUs &&
            vision.phase != SquatPhase.NOT_TRACKED
        when {
            // No camera view of the lifter: inertial-only operation.
            !visionTracking -> emitFault(fault, out)
            inRep() -> emitFault(fault.copy(channel = Channel.FUSED), out)
            // The camera pipeline lags the pod by a few frames; give it a moment to catch up.
            else -> pendingImuFault = fault
        }
    }

    private fun resolvePending(tUs: Long, out: MutableList<CoachEvent>) {
        val pending = pendingImuFault ?: return
        if (inRep()) {
            pendingImuFault = null
            emitFault(pending.copy(channel = Channel.FUSED), out)
        } else if (tUs - pending.tConfirmUs > config.visionConfirmWindowUs) {
            pendingImuFault = null
            out += CoachEvent.FaultVetoed(pending)
        }
    }

    private fun inRep(): Boolean = vision.phase == SquatPhase.DESCENT || vision.phase == SquatPhase.ASCENT

    private fun imuAlive(tUs: Long): Boolean = lastImuUs != Long.MIN_VALUE && tUs - lastImuUs < config.imuAliveUs

    private fun emitFault(fault: FaultDetected, out: MutableList<CoachEvent>) {
        if (fault.channel != Channel.VISION) imuFaultThisRep = true
        // Vision faults were already forwarded by onPoseFrame; IMU and fused ones are added here.
        if (fault.channel != Channel.VISION) out += fault
        val now = nowUs()
        when (val decision = cues.decide(fault, now)) {
            is CueDecision.Speak -> out += CoachEvent.CueIssued(decision.cue, fault, now)
            is CueDecision.Suppress -> out += CoachEvent.CueSuppressed(fault, decision.reason)
        }
    }
}
