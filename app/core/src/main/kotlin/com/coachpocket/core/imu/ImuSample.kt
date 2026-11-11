package com.coachpocket.core.imu

/**
 * One 6-axis IMU sample in SI units.
 *
 * [tUs] is in microseconds. Its clock depends on where the sample is in the pipeline:
 * the pod clock straight out of the decoder, the phone clock after [com.coachpocket.core.sync.ClockSync].
 * Acceleration is specific force in m/s^2 (reads +g along "up" at rest), angular rate in rad/s.
 */
data class ImuSample(
    val tUs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
)
