package com.coachpocket.core

import com.coachpocket.core.pose.Landmark
import com.coachpocket.core.pose.PoseFrame
import com.coachpocket.core.pose.PoseIndex
import com.coachpocket.core.sim.SquatMotion
import kotlin.math.PI
import kotlin.math.cos

/** Synthetic lifter seen by a portrait 3:4 camera. */
object Synthetic {
    const val ASPECT = 0.75f
    private const val HIP_Y = 0.5f
    private const val ANKLE_Y = 0.9f

    /**
     * @param h relative hip height (1 standing).
     * @param valgus knee displacement toward the midline as a fraction of leg length.
     * @param frontal false = seen from the side (hips and legs overlap in the image).
     */
    fun frame(tUs: Long, h: Float, valgus: Float = 0f, frontal: Boolean = true, visible: Boolean = true): PoseFrame {
        val lm = MutableList(PoseIndex.COUNT) { Landmark(0.5f, 0.3f, 0f, if (visible) 1f else 0.1f) }
        val hipY = ANKLE_Y - (ANKLE_Y - HIP_Y) * h
        val hipHalf = if (frontal) 0.08f else 0.005f
        val ankleHalf = if (frontal) 0.10f else 0.005f
        val vis = if (visible) 1f else 0.1f
        for (left in listOf(true, false)) {
            val s = if (left) 1f else -1f
            val hipX = 0.5f + s * hipHalf
            val ankleX = 0.5f + s * ankleHalf
            val kneeY = (hipY + ANKLE_Y) / 2
            val lineX = (hipX + ankleX) / 2
            val len = Math.hypot(((ankleX - hipX) * ASPECT).toDouble(), (ANKLE_Y - hipY).toDouble()).toFloat()
            val kneeX = lineX - s * valgus * len / ASPECT
            lm[if (left) PoseIndex.L_HIP else PoseIndex.R_HIP] = Landmark(hipX, hipY, 0f, vis)
            lm[if (left) PoseIndex.L_KNEE else PoseIndex.R_KNEE] = Landmark(kneeX, kneeY, 0f, vis)
            lm[if (left) PoseIndex.L_ANKLE else PoseIndex.R_ANKLE] = Landmark(ankleX, ANKLE_Y, 0f, vis)
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

    /** Height for a repeating list of motions after [leadInS] of standing; mirrors SimulatedPod. */
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
