package com.coachpocket.core.pose

import kotlin.math.acos
import kotlin.math.sqrt

/** A pose keypoint. Meaning of the coordinates depends on which list of [PoseFrame] it is in. */
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)

/**
 * One pose estimate.
 *
 * @param tUs capture time of the camera frame on the phone clock, microseconds.
 * @param image 33 BlazePose keypoints, x and y normalised to [0,1] of the upright image, y down.
 * @param world the same keypoints in metres, origin at the hip centre (may be empty).
 * @param aspect upright image width / height, needed to compare x and y distances.
 */
class PoseFrame(
    val tUs: Long,
    val image: List<Landmark>,
    val world: List<Landmark>,
    val aspect: Float,
)

/** BlazePose / MediaPipe Pose Landmarker topology. Left and right are the subject's own. */
object PoseIndex {
    const val NOSE = 0
    const val L_SHOULDER = 11
    const val R_SHOULDER = 12
    const val L_ELBOW = 13
    const val R_ELBOW = 14
    const val L_WRIST = 15
    const val R_WRIST = 16
    const val L_HIP = 23
    const val R_HIP = 24
    const val L_KNEE = 25
    const val R_KNEE = 26
    const val L_ANKLE = 27
    const val R_ANKLE = 28
    const val L_HEEL = 29
    const val R_HEEL = 30
    const val L_FOOT = 31
    const val R_FOOT = 32
    const val COUNT = 33

    /** Bones drawn by the overlay. */
    val SKELETON: List<Pair<Int, Int>> = listOf(
        L_SHOULDER to R_SHOULDER, L_SHOULDER to L_ELBOW, L_ELBOW to L_WRIST,
        R_SHOULDER to R_ELBOW, R_ELBOW to R_WRIST,
        L_SHOULDER to L_HIP, R_SHOULDER to R_HIP, L_HIP to R_HIP,
        L_HIP to L_KNEE, L_KNEE to L_ANKLE, R_HIP to R_KNEE, R_KNEE to R_ANKLE,
        L_ANKLE to L_HEEL, L_HEEL to L_FOOT, L_ANKLE to L_FOOT,
        R_ANKLE to R_HEEL, R_HEEL to R_FOOT, R_ANKLE to R_FOOT,
    )
}

object Geometry {
    /** Interior angle at [b] between rays b->a and b->c, degrees, using x, y and z. */
    fun angleDeg(a: Landmark, b: Landmark, c: Landmark): Float {
        val ux = a.x - b.x
        val uy = a.y - b.y
        val uz = a.z - b.z
        val vx = c.x - b.x
        val vy = c.y - b.y
        val vz = c.z - b.z
        val nu = sqrt(ux * ux + uy * uy + uz * uz)
        val nv = sqrt(vx * vx + vy * vy + vz * vz)
        if (nu < 1e-6f || nv < 1e-6f) return Float.NaN
        val cos = ((ux * vx + uy * vy + uz * vz) / (nu * nv)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }
}
