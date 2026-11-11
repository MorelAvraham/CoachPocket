package com.coachpocket.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli(PrintStream(out, true), PrintStream(err, true)).run(args.toList())
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun simulateThenEvaluateEndToEnd() {
        val dir = Files.createTempDirectory("cp-tools").toFile()
        try {
            val (c1, o1, _) = run("simulate", dir.path, "--subjects", "2", "--reps", "4")
            assertEquals(0, c1)
            assertTrue("wrote 12 synthetic sessions (48 repetitions)" in o1, o1)

            val reportFile = File(dir, "report.md")
            val (c2, o2, _) = run("eval", dir.path, "--out", reportFile.path)
            assertEquals(0, c2)
            assertTrue("48 repetitions (16 clean)" in o2, o2)
            assertTrue("Synthetic data" in o2 && "### Subject SIM2" in o2)
            assertEquals(o2, reportFile.readText())

            val session = dir.listFiles { f -> f.isDirectory }!!.first { "too_fast" in it.name }
            val (c3, o3, _) = run("replay", session.path, "--mode", "imu_only")
            assertEquals(0, c3)
            assertTrue("CUE \"Slow down\"" in o3, o3)

            val (c4, o4, _) = run("link", session.path)
            assertEquals(0, c4)
            assertTrue("lost samples         0" in o4 && "rate met" in o4, o4)

            assertEquals(0, run("sync", session.path).first)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun badInputIsReportedNotThrown() {
        assertEquals(2, run("eval", "/nonexistent/path").first)
        assertEquals(2, run("frobnicate", ".").first)
        assertEquals(2, run("replay", ".", "--mode").first)
        val (code, out, _) = run("--help")
        assertEquals(0, code)
        assertTrue("replay" in out)
    }
}
