package com.coachpocket.core.eval

import com.coachpocket.core.pose.PoseIndex
import com.coachpocket.core.session.RecordedSession
import com.coachpocket.core.util.fmt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tap test (U2, requirement R2): the lifter taps the pod-wearing hand down on a surface several
 * times in view of the camera. Each tap is an acceleration spike for the pod and the instant the
 * wrist stops moving down for the camera. The difference between the two, after clock alignment,
 * is the synchronisation error.
 *
 * Resolution is limited by the camera: at 30 fps the impact falls somewhere inside a 33 ms frame
 * interval and is placed at the frame where the wrist's downward speed collapses, so individual
 * taps carry up to about half a frame of error. The mean over many taps is the systematic offset,
 * which is what to compare against the 10 ms target; the spread is mostly frame quantisation.
 */
class TapSyncReport(
    /** IMU tap time minus camera tap time per matched tap, milliseconds. */
    val errorsMs: List<Double>,
    val imuTaps: Int,
    val cameraTaps: Int,
) {
    val meanMs: Double get() = if (errorsMs.isEmpty()) Double.NaN else errorsMs.average()
    val maxAbsMs: Double get() = errorsMs.maxOfOrNull { abs(it) } ?: Double.NaN
    val stdMs: Double
        get() {
            if (errorsMs.size < 2) return Double.NaN
            val m = meanMs
            return sqrt(errorsMs.sumOf { (it - m) * (it - m) } / (errorsMs.size - 1))
        }

    fun text(): String = buildString {
        append(fmt("taps seen            %d by the pod, %d by the camera, %d matched\n", imuTaps, cameraTaps, errorsMs.size))
        if (errorsMs.isEmpty()) {
            append("no matched taps: tap the pod hand firmly on a surface, 5 to 10 times, fully in frame\n")
            return@buildString
        }
        append(fmt("sync error           mean %+.1f ms, std %.1f ms, worst %.1f ms\n", meanMs, stdMs, maxAbsMs))
        append(fmt("requirement R2       %s (|mean| within 33 ms; target 10 ms)\n", if (abs(meanMs) <= 33) "met" else "NOT met"))
        if (errorsMs.size < 5) append("fewer than 5 taps: repeat the test before quoting this number\n")
    }

    companion object {
        private const val G = 9.80665
        private const val SPIKE = 12.0 // m/s^2 away from 1 g
        private const val REFRACTORY_US = 300_000L
        private const val MATCH_US = 150_000L

        fun of(session: RecordedSession): TapSyncReport {
            val imuTaps = imuTaps(session)
            val camTaps = cameraTaps(session)
            val errors = ArrayList<Double>()
            for (t in imuTaps) {
                val nearest = camTaps.minByOrNull { abs(it - t) } ?: continue
                if (abs(nearest - t) <= MATCH_US) errors += (t - nearest) / 1000.0
            }
            return TapSyncReport(errors, imuTaps.size, camTaps.size)
        }

        /** First sample of each acceleration spike, on the phone clock. */
        private fun imuTaps(session: RecordedSession): List<Long> {
            val taps = ArrayList<Long>()
            var last = Long.MIN_VALUE
            for (row in session.imu) {
                val s = row.sample
                val mag = sqrt((s.ax * s.ax + s.ay * s.ay + s.az * s.az).toDouble())
                if (abs(mag - G) > SPIKE && (last == Long.MIN_VALUE || s.tUs - last > REFRACTORY_US)) {
                    taps += s.tUs; last = s.tUs
                }
            }
            return taps
        }

        /** Instants at which a wrist moving down stops abruptly, from whichever wrist moves more. */
        private fun cameraTaps(session: RecordedSession): List<Long> {
            val frames = session.poses.map { it.frame }.filter { it.image.size >= PoseIndex.COUNT }
            if (frames.size < 5) return emptyList()
            fun range(i: Int): Float {
                val ys = frames.filter { it.image[i].visibility >= 0.5f }.map { it.image[i].y }
                return if (ys.size < 5) 0f else ys.max() - ys.min()
            }
            val wrist = if (range(PoseIndex.L_WRIST) >= range(PoseIndex.R_WRIST)) PoseIndex.L_WRIST else PoseIndex.R_WRIST
            val pts = frames.filter { it.image[wrist].visibility >= 0.5f }
            if (pts.size < 5) return emptyList()

            // Downward speed between consecutive frames, in image heights per second (y grows downward).
            val speed = DoubleArray(pts.size)
            for (i in 1 until pts.size) {
                val dt = (pts[i].tUs - pts[i - 1].tUs) / 1e6
                if (dt > 0 && dt < 0.2) speed[i] = (pts[i].image[wrist].y - pts[i - 1].image[wrist].y) / dt
            }
            val peak = speed.max()
            if (peak <= 0.2) return emptyList() // the hand never moved fast enough to be a tap
            val fast = maxOf(0.2, 0.4 * peak)
            val taps = ArrayList<Long>()
            var last = Long.MIN_VALUE
            for (i in 2 until pts.size) {
                // Moving down fast over the previous interval, (nearly) stopped over this one:
                // the impact happened at the frame that separates the two intervals.
                if (speed[i - 1] > fast && speed[i] < 0.25 * speed[i - 1]) {
                    val t = pts[i - 1].tUs
                    if (last == Long.MIN_VALUE || t - last > REFRACTORY_US) {
                        taps += t; last = t
                    }
                }
            }
            return taps
        }
    }
}
