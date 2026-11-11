package com.coachpocket.core.imu

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Thresholds for the inertial tempo analysis. Engineering defaults, NOT validated values:
 * they are to be fitted on the clean-vs-faulty comparison of test U4.
 */
data class TempoConfig(
    /** Downward speed that counts as "descending", m/s. */
    val descentStartSpeed: Float = 0.12f,
    val descentEndSpeed: Float = 0.03f,
    /** Vertical travel below which a descent is ignored, m. */
    val minDescentM: Float = 0.12f,
    /** Sustained downward speed above which the descent is called too fast before it ends, m/s. */
    val maxDescentSpeed: Float = 0.8f,
    val maxSpeedHoldUs: Long = 30_000,
    val minTravelForEarlyFaultM: Float = 0.08f,
    /** Eccentric phases shorter than this are a fault, microseconds. */
    val minEccentricUs: Long = 1_000_000,
    val stationaryAccelStd: Float = 0.25f,
    val stationaryAccelErr: Float = 0.35f,
    val stationaryGyro: Float = 0.25f,
    val stationaryHoldUs: Long = 400_000,
    val gravityTimeConstantS: Float = 2f,
    val velocityLeakS: Float = 20f,
)

sealed interface TempoEvent {
    /** Raised during the descent, as soon as the speed limit is exceeded. */
    data class TooFast(val tOnsetUs: Long, val tConfirmUs: Long, val speed: Float) : TempoEvent

    data class DescentEnded(
        val tStartUs: Long,
        val tEndUs: Long,
        val peakSpeed: Float,
        val travelM: Float,
        val flaggedEarly: Boolean,
    ) : TempoEvent {
        val durationUs: Long get() = tEndUs - tStartUs
    }
}

/**
 * Vertical-velocity tracker for a pod worn on the wrist of a lifter holding a barbell.
 *
 * Gravity direction is tracked with a gyro-propagated, accelerometer-corrected estimate, vertical
 * acceleration is integrated to velocity, and drift is removed with zero-velocity updates while
 * the pod is at rest. A slow, smooth descent is indistinguishable from rest for an accelerometer
 * around its midpoint, so the caller should clear [zeroVelocityAllowed] whenever another channel
 * (vision) knows the lifter is moving.
 */
class ImuDescentAnalyzer(private val config: TempoConfig = TempoConfig()) {
    @Volatile
    var zeroVelocityAllowed = true

    /** Current vertical velocity, m/s, positive up. */
    var velocity = 0f; private set
    var isStationary = false; private set

    private var initialised = false
    private var lastUs = 0L
    private var ux = 0f
    private var uy = 0f
    private var uz = 1f
    private var initCount = 0
    private var initX = 0f
    private var initY = 0f
    private var initZ = 0f

    private var magMean = G
    private var magVar = 0f
    private var gyroMean = 0f
    private var stationarySinceUs = Long.MIN_VALUE

    private var descending = false
    private var descentStartUs = 0L
    private var lastStillUs = 0L
    private var travel = 0f
    private var peak = 0f
    private var overSpeedSinceUs = Long.MIN_VALUE
    private var flagged = false

    fun reset() {
        initialised = false; initCount = 0; initX = 0f; initY = 0f; initZ = 0f
        velocity = 0f; descending = false; stationarySinceUs = Long.MIN_VALUE
        magMean = G; magVar = 0f; gyroMean = 0f
    }

    /** Feed one sample; [out] receives at most two events. Samples must be in time order. */
    fun process(s: ImuSample, out: MutableList<TempoEvent>) {
        if (!initialised) {
            // Average the first 50 samples for the initial "up" direction.
            initX += s.ax; initY += s.ay; initZ += s.az
            if (++initCount >= 50) {
                val n = sqrt(initX * initX + initY * initY + initZ * initZ)
                if (n > 1e-3f) {
                    ux = initX / n; uy = initY / n; uz = initZ / n
                }
                initialised = true
                lastUs = s.tUs
            }
            return
        }
        val dtUs = s.tUs - lastUs
        if (dtUs <= 0) return
        lastUs = s.tUs
        if (dtUs > 200_000) {
            // Stream gap: velocity is no longer trustworthy.
            velocity = 0f; descending = false; stationarySinceUs = Long.MIN_VALUE
            return
        }
        val dt = dtUs / 1e6f

        // 1. Rotate the "up" estimate with the gyro: du/dt = -omega x u (vector fixed in the world).
        val cx = s.gy * uz - s.gz * uy
        val cy = s.gz * ux - s.gx * uz
        val cz = s.gx * uy - s.gy * ux
        ux -= cx * dt; uy -= cy * dt; uz -= cz * dt

        // 2. Pull it toward the accelerometer when the reading is close to 1 g.
        val mag = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        if (abs(mag - G) < 1.0f && mag > 1e-3f) {
            val k = dt / config.gravityTimeConstantS
            ux += k * (s.ax / mag - ux); uy += k * (s.ay / mag - uy); uz += k * (s.az / mag - uz)
        }
        val un = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= un; uy /= un; uz /= un

        // 3. Rest detection on exponentially weighted statistics (time constant 100 ms).
        val a = 1f - exp(-dt / 0.1f)
        val diff = mag - magMean
        magMean += a * diff
        magVar += a * (diff * diff - magVar)
        val gyro = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
        gyroMean += a * (gyro - gyroMean)
        // The instantaneous check ends "rest" on the very sample the movement starts.
        val quiet = abs(mag - G) < config.stationaryAccelErr && sqrt(magVar) < config.stationaryAccelStd &&
            abs(magMean - G) < config.stationaryAccelErr && gyroMean < config.stationaryGyro
        if (quiet) {
            if (stationarySinceUs == Long.MIN_VALUE) stationarySinceUs = s.tUs
        } else {
            stationarySinceUs = Long.MIN_VALUE
        }
        isStationary = quiet && s.tUs - stationarySinceUs >= config.stationaryHoldUs

        // 4. Integrate vertical acceleration.
        val aVert = s.ax * ux + s.ay * uy + s.az * uz - G
        if (isStationary && zeroVelocityAllowed) {
            velocity = 0f
            descending = false
            lastStillUs = s.tUs
            return
        }
        velocity = (velocity + aVert * dt) * exp(-dt / config.velocityLeakS)

        // 5. Descent state machine.
        val down = -velocity
        if (!descending) {
            if (down < config.descentEndSpeed) lastStillUs = s.tUs
            if (down > config.descentStartSpeed) {
                descending = true
                // Back-date the start to the last moment the pod was not yet moving down.
                descentStartUs = if (s.tUs - lastStillUs < 1_000_000) lastStillUs else s.tUs
                travel = 0f; peak = down; flagged = false
                overSpeedSinceUs = Long.MIN_VALUE
            }
            return
        }
        travel += down * dt
        peak = max(peak, down)
        if (!flagged) {
            if (down > config.maxDescentSpeed) {
                if (overSpeedSinceUs == Long.MIN_VALUE) overSpeedSinceUs = s.tUs
                if (s.tUs - overSpeedSinceUs >= config.maxSpeedHoldUs && travel >= config.minTravelForEarlyFaultM) {
                    flagged = true
                    out += TempoEvent.TooFast(overSpeedSinceUs, s.tUs, down)
                }
            } else {
                overSpeedSinceUs = Long.MIN_VALUE
            }
        }
        if (down < config.descentEndSpeed) {
            descending = false
            if (travel >= config.minDescentM) {
                out += TempoEvent.DescentEnded(descentStartUs, s.tUs, peak, travel, flagged)
            }
        }
    }

    private companion object {
        const val G = 9.80665f
    }
}
