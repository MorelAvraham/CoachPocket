package com.coachpocket.core.sim

import com.coachpocket.core.pose.Landmark
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.pose.PoseIndex
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos

/**
 * A stick-figure lifter as a portrait 3:4 camera would see it. Used by the unit tests and by the
 * synthetic-dataset generator; it is a geometry fixture, not a model of real pose-estimation error.
 */
object SyntheticLifter {
    const val ASPECT = 0.75f
    private const val HIP_Y = 0.5f
    private const val ANKLE_Y = 0.9f

    /**
     * @param h relative hip height (1 standing).
     * @param valgus knee displacement toward the midline as a fraction of leg length.
     * @param frontal false = seen from the side (hips and legs overlap in the image).
     * @param noise standard deviation of keypoint jitter in normalised image units.
     */
    fun frame(
        tUs: Long,
        h: Float,
        valgus: Float = 0f,
        frontal: Boolean = true,
        visible: Boolean = true,
        noise: Float = 0f,
        rng: Random? = null,
    ): PoseFrame {
        val vis = if (visible) 1f else 0.1f
        val lm = MutableList(PoseIndex.COUNT) { Landmark(0.5f, 0.3f, 0f, vis) }
        val hipY = ANKLE_Y - (ANKLE_Y - HIP_Y) * h
        val hipHalf = if (frontal) 0.08f else 0.005f
        val ankleHalf = if (frontal) 0.10f else 0.005f
        fun jitter() = if (noise > 0f && rng != null) (rng.nextGaussian() * noise).toFloat() else 0f
        for (left in listOf(true, false)) {
            val s = if (left) 1f else -1f
            val hipX = 0.5f + s * hipHalf
            val ankleX = 0.5f + s * ankleHalf
            val kneeY = (hipY + ANKLE_Y) / 2
            val lineX = (hipX + ankleX) / 2
            val len = Math.hypot(((ankleX - hipX) * ASPECT).toDouble(), (ANKLE_Y - hipY).toDouble()).toFloat()
            val kneeX = lineX - s * valgus * len / ASPECT
            lm[if (left) PoseIndex.L_HIP else PoseIndex.R_HIP] = Landmark(hipX + jitter(), hipY + jitter(), 0f, vis)
            lm[if (left) PoseIndex.L_KNEE else PoseIndex.R_KNEE] = Landmark(kneeX + jitter(), kneeY + jitter(), 0f, vis)
            lm[if (left) PoseIndex.L_ANKLE else PoseIndex.R_ANKLE] = Landmark(ankleX + jitter(), ANKLE_Y + jitter(), 0f, vis)
        }
        return PoseFrame(tUs, lm, emptyList(), ASPECT)
    }

    /** Relative hip height at [t] seconds into a repetition that bottoms out at [bottomH]. */
    fun height(m: SquatMotion, t: Double, bottomH: Double = 0.5): Float {
        val drop = 1 - bottomH
        return when {
            t < 0 -> 1.0
            t < m.eccentricS -> 1 - drop * (1 - cos(PI * t / m.eccentricS)) / 2
            t < m.eccentricS + m.bottomPauseS -> bottomH
            t < m.eccentricS + m.bottomPauseS + m.concentricS ->
                bottomH + drop * (1 - cos(PI * (t - m.eccentricS - m.bottomPauseS) / m.concentricS)) / 2
            else -> 1.0
        }.toFloat()
    }

    /** Height for a repeating list of motions after [leadInS] of standing; mirrors [SimulatedPod]. */
    fun heightAt(motions: List<SquatMotion>, leadInS: Double, tS: Double): Float {
        if (tS < leadInS) return 1f
        var t = (tS - leadInS) % motions.sumOf { it.totalS }
        for (m in motions) {
            if (t < m.totalS) return height(m, t)
            t -= m.totalS
        }
        return 1f
    }
}
