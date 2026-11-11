package com.coachpocket.core.squat

import com.coachpocket.core.engine.Channel
import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.pose.Geometry
import com.coachpocket.core.pose.Landmark
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.pose.PoseIndex
import com.coachpocket.core.util.OneEuroFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import com.coachpocket.core.util.fmt

enum class SquatPhase { NOT_TRACKED, CALIBRATING, TOP, DESCENT, ASCENT }

/**
 * Thresholds for the vision side of the squat. Heights are relative hip height
 * h = (ankle y - hip y) / (the same while standing): 1 standing, about 0.5 at parallel.
 *
 * These are engineering defaults, NOT validated values. They are to be fitted on the labelled
 * dataset (M2/M3) and reviewed with the trainer.
 */
data class SquatConfig(
    val minVisibility: Float = 0.5f,
    val calibrationFrames: Int = 15,
    val descentStartH: Float = 0.93f,
    /**
     * The eccentric phase is timed from the last frame that was within this margin of the height
     * the lifter held just before going down (not from the later [descentStartH] crossing).
     */
    val descentOnsetMargin: Float = 0.012f,
    val repDepthH: Float = 0.78f,
    val topReturnH: Float = 0.94f,
    val bottomReboundH: Float = 0.025f,
    /** Give up on a repetition that has not finished after this long and recalibrate. */
    val repTimeoutUs: Long = 10_000_000,
    val trackingLostUs: Long = 700_000,
    /** Knee displacement toward the midline from the hip-ankle line, as a fraction of leg length. */
    val valgusOnThreshold: Float = 0.08f,
    val valgusOffRatio: Float = 0.6f,
    val valgusMinFrames: Int = 3,
    val valgusActiveBelowH: Float = 0.90f,
    /** Hip width / leg length below which the view is too side-on to judge valgus. */
    val frontalMinHipWidthRatio: Float = 0.18f,
    /** Vision-only tempo fallback, used when no pod is streaming. */
    val minEccentricUs: Long = 1_000_000,
)

class SquatFrameResult(
    val phase: SquatPhase,
    val relativeHipHeight: Float,
    val kneeAngleDeg: Float,
    val valgusDeviation: Float,
    val frontalView: Boolean,
    val events: List<CoachEvent>,
)

/**
 * Repetition segmentation and knee-valgus detection from pose keypoints.
 *
 * Segmentation uses relative hip height, which works from the front and from the side.
 * Valgus uses the frontal-plane position of each knee relative to its hip-ankle line and is
 * only evaluated when the camera sees the lifter roughly from the front.
 */
class SquatVisionTracker(private val config: SquatConfig = SquatConfig()) {
    var phase = SquatPhase.NOT_TRACKED; private set
    var repCount = 0; private set

    /** When true, a short eccentric is reported from vision alone (no pod available). */
    var visionTempoEnabled = true

    private val heightFilter = OneEuroFilter(minCutoffHz = 3.0, beta = 0.4, derivativeCutoffHz = 3.0)

    // Smooths the raw leg length before it feeds the standing reference: a running maximum of a
    // noisy signal creeps upward, which would make "standing" read as slightly squatted.
    private val legFilter = OneEuroFilter(minCutoffHz = 1.0, beta = 0.0)

    // Last ~2 s of (time, height) while standing, to find where a descent really began.
    private val historyT = LongArray(HISTORY)
    private val historyH = FloatArray(HISTORY)
    private var historyCount = 0
    private var historyNext = 0
    private var standingLegLen = 0f
    private var calibrationCount = 0
    private var lastValidUs = Long.MIN_VALUE
    private var lastFrameUs = Long.MIN_VALUE

    private var descentStartUs = 0L
    private var minH = 1f
    private var minHUs = 0L
    private var bottomUs = 0L
    private var eccentricUs = 0L

    private var valgusFrames = 0
    private var valgusOnsetUs = 0L
    private var valgusActive = false
    private var valgusReportedThisRep = false

    fun reset() {
        phase = SquatPhase.NOT_TRACKED
        repCount = 0
        recalibrate()
    }

    private fun recalibrate() {
        heightFilter.reset(); legFilter.reset()
        historyCount = 0; historyNext = 0
        standingLegLen = 0f; calibrationCount = 0
        valgusFrames = 0; valgusActive = false; valgusReportedThisRep = false
        if (phase != SquatPhase.NOT_TRACKED) phase = SquatPhase.CALIBRATING
    }

    fun process(frame: PoseFrame): SquatFrameResult {
        val lm = frame.image
        val t = frame.tUs
        val events = ArrayList<CoachEvent>(2)

        if (!legsVisible(lm)) {
            if (lastValidUs != Long.MIN_VALUE && t - lastValidUs > config.trackingLostUs && phase != SquatPhase.NOT_TRACKED) {
                phase = SquatPhase.NOT_TRACKED
                recalibrate()
            }
            return SquatFrameResult(phase, Float.NaN, Float.NaN, Float.NaN, false, events)
        }
        lastValidUs = t
        val dt = if (lastFrameUs == Long.MIN_VALUE) 0f else ((t - lastFrameUs) / 1e6f).coerceIn(0f, 0.5f)
        lastFrameUs = t

        val lHip = lm[PoseIndex.L_HIP]
        val rHip = lm[PoseIndex.R_HIP]
        val lAnkle = lm[PoseIndex.L_ANKLE]
        val rAnkle = lm[PoseIndex.R_ANKLE]
        val legLen = (lAnkle.y + rAnkle.y) / 2 - (lHip.y + rHip.y) / 2
        if (legLen <= 0.02f) return SquatFrameResult(phase, Float.NaN, Float.NaN, Float.NaN, false, events)

        // Standing reference: the tallest the legs have looked, decaying slowly so that the
        // reference follows the lifter stepping back from the camera.
        val smoothLeg = legFilter.filter(legLen.toDouble(), t / 1e6).toFloat()
        standingLegLen = max(smoothLeg, standingLegLen * (1f - 0.005f * dt))
        val h = heightFilter.filter((legLen / standingLegLen).toDouble(), t / 1e6).toFloat()
        val rising = heightFilter.derivative > 0
        historyT[historyNext] = t; historyH[historyNext] = h
        historyNext = (historyNext + 1) % HISTORY
        if (historyCount < HISTORY) historyCount++

        val hipWidth = abs(lHip.x - rHip.x) * frame.aspect
        val frontal = hipWidth / standingLegLen >= config.frontalMinHipWidthRatio
        val knee = kneeAngle(frame)
        val valgus = if (frontal) max(medialDeviation(lm, true, frame.aspect), medialDeviation(lm, false, frame.aspect)) else Float.NaN

        when (phase) {
            SquatPhase.NOT_TRACKED -> {
                phase = SquatPhase.CALIBRATING
                calibrationCount = 1
            }

            SquatPhase.CALIBRATING -> {
                if (++calibrationCount >= config.calibrationFrames && h > config.topReturnH) phase = SquatPhase.TOP
            }

            SquatPhase.TOP -> {
                if (h < config.descentStartH) {
                    phase = SquatPhase.DESCENT
                    // Crossing the threshold happens well into the movement; time it from the top.
                    descentStartUs = descentOnset(t)
                    minH = h; minHUs = t
                    valgusFrames = 0; valgusActive = false; valgusReportedThisRep = false
                    events += CoachEvent.RepStarted(t)
                }
            }

            SquatPhase.DESCENT -> {
                if (h < minH) {
                    minH = h; minHUs = t
                }
                if (h > config.topReturnH && minH > config.repDepthH) {
                    phase = SquatPhase.TOP // a dip, not a repetition
                } else if (rising && h > minH + config.bottomReboundH) {
                    if (minH <= config.repDepthH) {
                        phase = SquatPhase.ASCENT
                        bottomUs = minHUs
                        eccentricUs = bottomUs - descentStartUs
                        events += CoachEvent.BottomReached(bottomUs, minH, eccentricUs)
                        if (visionTempoEnabled && eccentricUs < config.minEccentricUs) {
                            events += CoachEvent.FaultDetected(
                                FaultType.TOO_FAST_DESCENT, Channel.VISION, bottomUs, t,
                                severity = config.minEccentricUs.toFloat() / max(eccentricUs, 1L),
                                detail = fmt("eccentric %.2f s (vision)", eccentricUs / 1e6),
                            )
                        }
                    }
                } else if (t - descentStartUs > config.repTimeoutUs) {
                    recalibrate()
                }
            }

            SquatPhase.ASCENT -> {
                if (h < minH) {
                    minH = h // sank again after the first rebound: keep the deepest point
                }
                if (h >= config.topReturnH) {
                    repCount++
                    events += CoachEvent.RepCompleted(repCount, t, eccentricUs, t - bottomUs, minH)
                    phase = SquatPhase.TOP
                } else if (t - descentStartUs > config.repTimeoutUs) {
                    recalibrate()
                }
            }
        }

        if (frontal && (phase == SquatPhase.DESCENT || phase == SquatPhase.ASCENT) && h < config.valgusActiveBelowH) {
            updateValgus(valgus, lm, frame.aspect, t, events)
        } else {
            valgusFrames = 0; valgusActive = false
        }

        return SquatFrameResult(phase, h, knee, valgus, frontal, events)
    }

    private fun updateValgus(dev: Float, lm: List<Landmark>, aspect: Float, t: Long, events: MutableList<CoachEvent>) {
        val on = config.valgusOnThreshold
        if (dev > on) {
            if (valgusFrames == 0) valgusOnsetUs = t
            valgusFrames++
            if (!valgusActive && valgusFrames >= config.valgusMinFrames) {
                valgusActive = true
                if (!valgusReportedThisRep) {
                    valgusReportedThisRep = true
                    val left = medialDeviation(lm, true, aspect)
                    val right = medialDeviation(lm, false, aspect)
                    val side = if (left > on && right > on) "both knees" else if (left > right) "left knee" else "right knee"
                    events += CoachEvent.FaultDetected(
                        FaultType.KNEE_VALGUS, Channel.VISION, valgusOnsetUs, t,
                        severity = dev / on, detail = fmt("$side, deviation %.3f", dev),
                    )
                }
            }
        } else if (dev < on * config.valgusOffRatio) {
            valgusFrames = 0; valgusActive = false
        } else if (!valgusActive) {
            valgusFrames = 0 // inside the hysteresis band without having confirmed: start over
        }
    }

    /**
     * Walks back from the newest frame to the last one that was still at the height held before
     * the descent. "The height held" is the median of the recent past, so neither a biased standing
     * reference nor a single jittery frame shifts the answer.
     */
    private fun descentOnset(nowUs: Long): Long {
        val recent = FloatArray(historyCount)
        var n = 0
        for (k in 1..historyCount) {
            val i = (historyNext - k + HISTORY) % HISTORY
            if (nowUs - historyT[i] > ONSET_LOOKBACK_US) break
            recent[n++] = historyH[i]
        }
        if (n < 3) return nowUs
        val level = recent.copyOf(n).also { it.sort() }[n / 2] - config.descentOnsetMargin
        for (k in 0 until n) {
            if (recent[k] >= level) return historyT[(historyNext - 1 - k + HISTORY) % HISTORY]
        }
        return nowUs
    }

    private fun legsVisible(lm: List<Landmark>): Boolean {
        if (lm.size < PoseIndex.COUNT) return false
        for (i in LEG_POINTS) if (lm[i].visibility < config.minVisibility) return false
        return true
    }

    /**
     * How far the knee sits toward the body midline from the straight line hip-ankle, in the image
     * plane, as a fraction of the hip-ankle length. Positive = knee caving in.
     */
    private fun medialDeviation(lm: List<Landmark>, left: Boolean, aspect: Float): Float {
        val hip = lm[if (left) PoseIndex.L_HIP else PoseIndex.R_HIP]
        val knee = lm[if (left) PoseIndex.L_KNEE else PoseIndex.R_KNEE]
        val ankle = lm[if (left) PoseIndex.L_ANKLE else PoseIndex.R_ANKLE]
        val otherHip = lm[if (left) PoseIndex.R_HIP else PoseIndex.L_HIP]
        val hx = hip.x * aspect
        val kx = knee.x * aspect
        val ax = ankle.x * aspect
        val dy = ankle.y - hip.y
        val len = sqrt((ax - hx) * (ax - hx) + dy * dy)
        if (len < 1e-4f || abs(dy) < 1e-4f) return 0f
        val lineX = hx + (ax - hx) * ((knee.y - hip.y) / dy)
        val towardMidline = if (otherHip.x > hip.x) 1f else -1f
        return (kx - lineX) * towardMidline / len
    }

    private fun kneeAngle(frame: PoseFrame): Float {
        val w = frame.world
        if (w.size < PoseIndex.COUNT) return Float.NaN
        val l = Geometry.angleDeg(w[PoseIndex.L_HIP], w[PoseIndex.L_KNEE], w[PoseIndex.L_ANKLE])
        val r = Geometry.angleDeg(w[PoseIndex.R_HIP], w[PoseIndex.R_KNEE], w[PoseIndex.R_ANKLE])
        return (l + r) / 2
    }

    private companion object {
        const val HISTORY = 64
        const val ONSET_LOOKBACK_US = 1_500_000L
        val LEG_POINTS = intArrayOf(
            PoseIndex.L_HIP, PoseIndex.R_HIP, PoseIndex.L_KNEE, PoseIndex.R_KNEE, PoseIndex.L_ANKLE, PoseIndex.R_ANKLE,
        )
    }
}
