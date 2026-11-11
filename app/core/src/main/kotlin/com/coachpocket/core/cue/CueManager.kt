package com.coachpocket.core.cue

import com.coachpocket.core.engine.CoachEvent.FaultDetected
import com.coachpocket.core.engine.Cue
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.util.fmt

data class CueConfig(
    /** Minimum silence between any two cues. A higher-priority fault may cut in. */
    val globalGapUs: Long = 1_500_000,
    /** The same cue is not repeated more often than this. */
    val perFaultCooldownUs: Long = 6_000_000,
)

sealed interface CueDecision {
    data class Speak(val cue: Cue) : CueDecision

    data class Suppress(val reason: String) : CueDecision
}

/**
 * Decides whether a detected fault becomes an audible cue: at most one cue at a time, no nagging
 * with the same cue, and the more important fault wins when two compete.
 */
class CueManager(private val config: CueConfig = CueConfig()) {
    private val lastSpoken = LongArray(FaultType.entries.size) { Long.MIN_VALUE }
    private var lastCueUs = Long.MIN_VALUE
    private var lastCuePriority = 0

    var spoken = 0; private set
    var suppressed = 0; private set

    fun reset() {
        lastSpoken.fill(Long.MIN_VALUE)
        lastCueUs = Long.MIN_VALUE; lastCuePriority = 0; spoken = 0; suppressed = 0
    }

    fun decide(fault: FaultDetected, nowUs: Long): CueDecision {
        val type = fault.fault
        val previous = lastSpoken[type.ordinal]
        if (previous != Long.MIN_VALUE && nowUs - previous < config.perFaultCooldownUs) {
            suppressed++
            return CueDecision.Suppress(fmt("same cue given %.1f s ago", (nowUs - previous) / 1e6))
        }
        var interrupt = false
        if (lastCueUs != Long.MIN_VALUE && nowUs - lastCueUs < config.globalGapUs) {
            if (type.priority <= lastCuePriority) {
                suppressed++
                return CueDecision.Suppress("another cue is still playing")
            }
            interrupt = true
        }
        lastSpoken[type.ordinal] = nowUs
        lastCueUs = nowUs
        lastCuePriority = type.priority
        spoken++
        return CueDecision.Speak(Cue(type, type.cueText, interrupt))
    }
}
