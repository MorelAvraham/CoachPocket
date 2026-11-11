package com.coachpocket.core.engine

/** Technique faults the app can detect. Higher [priority] wins when cues compete. */
enum class FaultType(val cueText: String, val priority: Int) {
    KNEE_VALGUS("Knees out", 2),
    TOO_FAST_DESCENT("Slow down", 1),
}

enum class Channel { VISION, IMU, FUSED }

data class Cue(val fault: FaultType, val text: String, val interrupt: Boolean)

/** Everything the engine reports. All times are on the phone clock, microseconds. */
sealed interface CoachEvent {
    data class RepStarted(val tUs: Long) : CoachEvent

    data class BottomReached(val tUs: Long, val depth: Float, val eccentricUs: Long) : CoachEvent

    data class RepCompleted(
        val index: Int,
        val tUs: Long,
        val eccentricUs: Long,
        val concentricUs: Long,
        /** Lowest relative hip height reached: 1 = standing, about 0.5 = thighs parallel. */
        val depth: Float,
    ) : CoachEvent

    data class FaultDetected(
        val fault: FaultType,
        val channel: Channel,
        /** Sensor time at which the fault first became observable. */
        val tOnsetUs: Long,
        /** Sensor time of the sample that confirmed it. */
        val tConfirmUs: Long,
        val severity: Float,
        val detail: String,
    ) : CoachEvent

    data class CueIssued(
        val cue: Cue,
        val fault: FaultDetected,
        /** Wall time at which the engine decided to speak. */
        val tDecisionUs: Long,
    ) : CoachEvent

    data class CueSuppressed(val fault: FaultDetected, val reason: String) : CoachEvent

    /** An IMU fault that vision did not confirm as being inside a repetition. */
    data class FaultVetoed(val fault: FaultDetected) : CoachEvent
}
