package com.coachpocket.core.eval

import com.coachpocket.core.engine.ChannelMode
import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.engine.EngineConfig
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.session.RecordedSession
import com.coachpocket.core.util.fmt

class RepWindow(val index: Int, val startUs: Long, val endUs: Long, val truth: Set<FaultType>)

class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0) {
    val precision: Double get() = if (tp + fp == 0) Double.NaN else tp.toDouble() / (tp + fp)
    val recall: Double get() = if (tp + fn == 0) Double.NaN else tp.toDouble() / (tp + fn)
    val f1: Double get() = if (2 * tp + fp + fn == 0) Double.NaN else 2.0 * tp / (2 * tp + fp + fn)

    operator fun plusAssign(o: Counts) {
        tp += o.tp; fp += o.fp; fn += o.fn
    }
}

/** Result of one session (or a sum of sessions) in one channel mode. */
class ModeResult(val mode: ChannelMode) {
    val perFault: Map<FaultType, Counts> = FaultType.entries.associateWith { Counts() }
    var reps = 0
    var cleanReps = 0
    var expectedReps = 0
    var sessionsWithExpectedReps = 0
    var repCountAbsError = 0

    /** Cues spoken for a fault the repetition did not have, on clean repetitions (the R7 numerator). */
    var falseCuesOnCleanReps = 0
    var falseCuesOnFaultyReps = 0
    var cuesOutsideReps = 0
    var correctCues = 0

    /** Fault onset to cue decision, milliseconds, for correct cues. */
    val latenciesMs = ArrayList<Double>()

    /** R7: false cues per 10 clean repetitions. */
    val falseCuesPer10Clean: Double get() = if (cleanReps == 0) Double.NaN else 10.0 * falseCuesOnCleanReps / cleanReps

    fun latencyPercentile(p: Double): Double {
        if (latenciesMs.isEmpty()) return Double.NaN
        val s = latenciesMs.sorted()
        return s[Math.round(p / 100 * (s.size - 1)).toInt()]
    }

    operator fun plusAssign(o: ModeResult) {
        for (f in FaultType.entries) perFault.getValue(f) += o.perFault.getValue(f)
        reps += o.reps; cleanReps += o.cleanReps
        expectedReps += o.expectedReps; sessionsWithExpectedReps += o.sessionsWithExpectedReps
        repCountAbsError += o.repCountAbsError
        falseCuesOnCleanReps += o.falseCuesOnCleanReps; falseCuesOnFaultyReps += o.falseCuesOnFaultyReps
        cuesOutsideReps += o.cuesOutsideReps; correctCues += o.correctCues
        latenciesMs += o.latenciesMs
    }
}

/**
 * Scores the engine against ground truth, repetition by repetition, in each channel mode.
 *
 * Unit of scoring: one repetition x one fault type. A repetition counts as "detected with fault X"
 * if the engine raised X with an onset inside that repetition, whether or not the cue was spoken
 * (repeat cues are deliberately held back by the cue manager, which must not count as a miss).
 * False cues are counted separately on what the lifter would actually hear.
 *
 * Repetition boundaries come from the camera channel in every mode, so the three modes are scored
 * on identical repetitions. Sessions the camera cannot segment contribute no repetitions; that
 * shows up as a repetition-count error, not as a silent pass.
 */
object Evaluator {
    private const val BEFORE_US = 700_000L
    private const val AFTER_US = 300_000L

    fun repWindows(session: RecordedSession, config: EngineConfig = EngineConfig()): List<RepWindow> {
        val events = Replay.run(session, ChannelMode.VISION_ONLY, config)
        val raw = ArrayList<LongArray>()
        var start = Long.MIN_VALUE
        for (e in events) {
            when (e) {
                is CoachEvent.RepStarted -> start = e.tUs
                is CoachEvent.RepCompleted -> if (start != Long.MIN_VALUE) {
                    raw += longArrayOf(start, e.tUs); start = Long.MIN_VALUE
                }
                else -> {}
            }
        }
        return raw.mapIndexed { i, w ->
            // Pad each window, but never past the midpoint to the neighbouring repetition.
            val lo = if (i == 0) w[0] - BEFORE_US else maxOf(w[0] - BEFORE_US, (raw[i - 1][1] + w[0]) / 2)
            val hi = if (i == raw.lastIndex) w[1] + AFTER_US else minOf(w[1] + AFTER_US, (w[1] + raw[i + 1][0]) / 2)
            RepWindow(i + 1, lo, hi, session.labelOf(i + 1))
        }
    }

    fun evaluate(session: RecordedSession, mode: ChannelMode, config: EngineConfig = EngineConfig()): ModeResult {
        val windows = repWindows(session, config)
        val events = Replay.run(session, mode, config)
        val result = ModeResult(mode)
        result.reps = windows.size
        result.cleanReps = windows.count { it.truth.isEmpty() }
        session.meta.expectedReps?.let {
            result.expectedReps = it
            result.sessionsWithExpectedReps = 1
            result.repCountAbsError = kotlin.math.abs(windows.size - it)
        }

        fun windowOf(tUs: Long) = windows.firstOrNull { tUs >= it.startUs && tUs <= it.endUs }

        val detected = windows.associateWith { HashSet<FaultType>() }
        for (e in events) {
            when (e) {
                is CoachEvent.FaultDetected -> windowOf(e.tOnsetUs)?.let { detected.getValue(it) += e.fault }
                is CoachEvent.CueIssued -> {
                    val w = windowOf(e.fault.tOnsetUs)
                    when {
                        w == null -> result.cuesOutsideReps++
                        e.cue.fault in w.truth -> {
                            result.correctCues++
                            result.latenciesMs += (e.tDecisionUs - e.fault.tOnsetUs) / 1000.0
                        }
                        w.truth.isEmpty() -> result.falseCuesOnCleanReps++
                        else -> result.falseCuesOnFaultyReps++
                    }
                }
                else -> {}
            }
        }
        for (w in windows) {
            for (f in FaultType.entries) {
                val c = result.perFault.getValue(f)
                val truth = f in w.truth
                val hit = f in detected.getValue(w)
                if (truth && hit) c.tp++ else if (!truth && hit) c.fp++ else if (truth) c.fn++
            }
        }
        return result
    }

    /** Sums the per-session results of every mode. */
    fun evaluateAll(sessions: List<RecordedSession>, config: EngineConfig = EngineConfig()): Map<ChannelMode, ModeResult> =
        ChannelMode.entries.associateWith { mode ->
            ModeResult(mode).also { total -> for (s in sessions) total += evaluate(s, mode, config) }
        }

    private fun num(v: Double, pattern: String = "%.2f") = if (v.isNaN()) "n/a" else fmt(pattern, v)

    fun markdown(title: String, results: Map<ChannelMode, ModeResult>): String {
        val sb = StringBuilder()
        val any = results.values.first()
        sb.append("### ").append(title).append("\n\n")
        sb.append(fmt("%d repetitions (%d clean)", any.reps, any.cleanReps))
        if (any.sessionsWithExpectedReps > 0) {
            sb.append(fmt("; repetition count off by %d in total against %d expected", any.repCountAbsError, any.expectedReps))
        }
        sb.append(".\n\n| Fault | Mode | Precision | Recall | F1 | TP | FP | FN |\n|---|---|---|---|---|---|---|---|\n")
        for (f in FaultType.entries) {
            for ((mode, r) in results) {
                val c = r.perFault.getValue(f)
                sb.append("| ${f.name.lowercase()} | ${mode.name.lowercase()} | ${num(c.precision)} | ${num(c.recall)} | ${num(c.f1)} | ${c.tp} | ${c.fp} | ${c.fn} |\n")
            }
        }
        sb.append("\n| Mode | False cues / 10 clean reps | False cues on faulty reps | Cues outside reps | Correct cues | Latency p50 | Latency p95 |\n|---|---|---|---|---|---|---|\n")
        for ((mode, r) in results) {
            sb.append(
                "| ${mode.name.lowercase()} | ${num(r.falseCuesPer10Clean)} | ${r.falseCuesOnFaultyReps} | ${r.cuesOutsideReps} | ${r.correctCues} | " +
                    "${num(r.latencyPercentile(50.0), "%.0f ms")} | ${num(r.latencyPercentile(95.0), "%.0f ms")} |\n",
            )
        }
        return sb.toString()
    }
}
