package com.coachpocket.tools

import com.coachpocket.core.engine.ChannelMode
import com.coachpocket.core.engine.CoachEvent
import com.coachpocket.core.engine.FaultType
import com.coachpocket.core.eval.Evaluator
import com.coachpocket.core.eval.LinkReport
import com.coachpocket.core.eval.Replay
import com.coachpocket.core.eval.TapSyncReport
import com.coachpocket.core.session.RecordedSession
import com.coachpocket.core.session.SessionReader
import com.coachpocket.core.session.SessionWriter
import com.coachpocket.core.sim.SyntheticSession
import com.coachpocket.core.sim.SyntheticSetSpec
import com.coachpocket.core.util.fmt
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE = """CoachPocket offline tools. Sessions are the folders the app records.

  replay   <session> [--mode fused|vision_only|imu_only]   run the engine over one session, print events
  eval     <sessions> [--out report.md]                    per-fault precision/recall/F1 in all three modes
  link     <session>                                       BLE link statistics (test U1)
  sync     <session>                                       tap-test clock alignment error (test U2)
  simulate <out-dir> [--subjects 3] [--reps 5]             write a SYNTHETIC dataset to try the pipeline on

<sessions> may be one session folder or a folder containing many."""

fun main(args: Array<String>) {
    exitProcess(Cli(System.out, System.err).run(args.toList()))
}

class Cli(private val out: PrintStream, private val err: PrintStream) {
    fun run(args: List<String>): Int {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            out.println(USAGE)
            return 0
        }
        val positional = ArrayList<String>()
        val options = HashMap<String, String>()
        var i = 1
        while (i < args.size) {
            if (args[i].startsWith("--")) {
                if (i + 1 >= args.size) return fail("option ${args[i]} needs a value")
                options[args[i].removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                positional += args[i++]
            }
        }
        val target = positional.firstOrNull() ?: return fail("missing path\n\n$USAGE")
        return try {
            when (args[0]) {
                "replay" -> replay(File(target), options)
                "eval" -> eval(File(target), options)
                "link" -> link(File(target))
                "sync" -> sync(File(target))
                "simulate" -> simulate(File(target), options)
                else -> fail("unknown command '${args[0]}'\n\n$USAGE")
            }
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "bad input")
        }
    }

    private fun fail(message: String): Int {
        err.println("error: $message")
        return 2
    }

    private fun one(dir: File): RecordedSession {
        require(SessionReader.isSession(dir)) { "$dir is not a session folder (no meta.properties)" }
        return SessionReader.read(dir)
    }

    private fun many(root: File): List<RecordedSession> {
        val dirs = SessionReader.find(root)
        require(dirs.isNotEmpty()) { "no sessions found under $root" }
        return dirs.map(SessionReader::read)
    }

    private fun mode(options: Map<String, String>): ChannelMode {
        val name = options["mode"] ?: return ChannelMode.FUSED
        return ChannelMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown mode '$name' (fused, vision_only, imu_only)")
    }

    private fun replay(dir: File, options: Map<String, String>): Int {
        val session = one(dir)
        val mode = mode(options)
        out.println("${session.name}: subject ${session.meta.subject}, condition ${session.meta.values["condition"] ?: "clean"}, " +
            "${session.poses.size} pose frames, ${session.imu.size} IMU samples, mode ${mode.name.lowercase()}")
        for (e in Replay.run(session, mode)) {
            val line = when (e) {
                is CoachEvent.RepStarted -> null
                is CoachEvent.BottomReached -> null
                is CoachEvent.RepCompleted -> fmt("%9.3f  rep %d: down %.2f s, up %.2f s, depth %.2f", e.tUs / 1e6, e.index, e.eccentricUs / 1e6, e.concentricUs / 1e6, e.depth)
                is CoachEvent.FaultDetected -> fmt("%9.3f  fault %s [%s] %s", e.tConfirmUs / 1e6, e.fault.name.lowercase(), e.channel.name.lowercase(), e.detail)
                is CoachEvent.CueIssued -> fmt("%9.3f  CUE \"%s\" %.0f ms after onset", e.tDecisionUs / 1e6, e.cue.text, (e.tDecisionUs - e.fault.tOnsetUs) / 1000.0)
                is CoachEvent.CueSuppressed -> fmt("%9.3f  cue held back: %s", e.fault.tConfirmUs / 1e6, e.reason)
                is CoachEvent.FaultVetoed -> fmt("%9.3f  pod fault vetoed by camera: %s", e.fault.tConfirmUs / 1e6, e.fault.detail)
            }
            if (line != null) out.println(line)
        }
        return 0
    }

    private fun eval(root: File, options: Map<String, String>): Int {
        val sessions = many(root)
        val report = StringBuilder()
        report.append("# CoachPocket evaluation\n\n")
        report.append("${sessions.size} sessions, subjects: ${sessions.map { it.meta.subject }.distinct().sorted().joinToString()}.\n\n")
        if (sessions.any { it.meta.values["synthetic"] == "true" }) {
            report.append("> **Synthetic data.** These numbers check that the pipeline runs. They are not results.\n\n")
        }
        report.append("Rule-based detectors have no training step, so every subject is an unseen subject; ")
        report.append("the per-subject tables show how much the scores depend on who is lifting.\n\n")
        report.append(Evaluator.markdown("All sessions", Evaluator.evaluateAll(sessions))).append('\n')
        val bySubject = sessions.groupBy { it.meta.subject }
        if (bySubject.size > 1) {
            for ((subject, group) in bySubject.toSortedMap()) {
                report.append(Evaluator.markdown("Subject $subject", Evaluator.evaluateAll(group))).append('\n')
            }
        }
        val unlabelled = sessions.count { "condition" !in it.meta.values && it.repLabels.isEmpty() }
        if (unlabelled > 0) report.append("Warning: $unlabelled sessions have no condition and no labels.csv and were scored as clean.\n")
        out.print(report)
        options["out"]?.let { File(it).writeText(report.toString()) }
        return 0
    }

    private fun link(dir: File): Int {
        val report = LinkReport.of(one(dir)) ?: return fail("the session has no IMU data")
        out.print(report.text())
        out.println(fmt("requirement R1       %s (1,000 samples/s nominal; loss is reported, not gated)",
            if (report.nominalRateHz >= 999.0) "rate met" else fmt("rate NOT met (%.0f Hz)", report.nominalRateHz)))
        return 0
    }

    private fun sync(dir: File): Int {
        out.print(TapSyncReport.of(one(dir)).text())
        return 0
    }

    private fun simulate(outDir: File, options: Map<String, String>): Int {
        val subjects = options["subjects"]?.toIntOrNull() ?: 3
        val reps = options["reps"]?.toIntOrNull() ?: 5
        val conditions = listOf(emptySet(), setOf(FaultType.KNEE_VALGUS), setOf(FaultType.TOO_FAST_DESCENT))
        var seed = 100L
        var count = 0
        for (s in 1..subjects) {
            for (condition in conditions) {
                repeat(2) {
                    val session = SyntheticSession.generate(SyntheticSetSpec("SIM$s", condition, reps, seed++))
                    SessionWriter.write(File(outDir, session.name), session)
                    count++
                }
            }
        }
        out.println("wrote $count synthetic sessions (${count * reps} repetitions) to $outDir")
        return 0
    }
}
