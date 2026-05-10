package com.michaelsgroi.test.leakdetector.attribution.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path

class AttributionCliTest {
    private val standalonePort8080Report =
        """
        {"type":"header","runId":"r","startedAt":"2024-01-01T00:00:00Z","monitors":["ports"],"snapshotGranularity":"class"}
        {"type":"snapshot","kind":"BASELINE","timestamp":"2024-01-01T00:00:00Z","testClass":null,"testMethod":null,"discrete":{"ports":[]},"numeric":{}}
        {"type":"snapshot","kind":"BEFORE_ALL","timestamp":"2024-01-01T00:00:01Z","testClass":"com.A","testMethod":null,"discrete":{"ports":[]},"numeric":{}}
        {"type":"snapshot","kind":"AFTER_ALL","timestamp":"2024-01-01T00:00:02Z","testClass":"com.A","testMethod":null,"discrete":{"ports":[8080]},"numeric":{}}
        {"type":"snapshot","kind":"FINAL","timestamp":"2024-01-01T00:00:03Z","testClass":null,"testMethod":null,"discrete":{"ports":[8080]},"numeric":{}}
        {"type":"footer","finishedAt":"2024-01-01T00:00:04Z","lifecycles":[{"testClass":"com.A","start":"2024-01-01T00:00:01Z","end":"2024-01-01T00:00:02Z"}]}
        """.trimIndent()

    private val secondRunPort8080AttributedToB =
        """
        {"type":"header","runId":"r2","startedAt":"2024-01-01T00:10:00Z","monitors":["ports"],"snapshotGranularity":"class"}
        {"type":"snapshot","kind":"BASELINE","timestamp":"2024-01-01T00:10:00Z","testClass":null,"testMethod":null,"discrete":{"ports":[]},"numeric":{}}
        {"type":"snapshot","kind":"BEFORE_ALL","timestamp":"2024-01-01T00:10:01Z","testClass":"com.B","testMethod":null,"discrete":{"ports":[]},"numeric":{}}
        {"type":"snapshot","kind":"AFTER_ALL","timestamp":"2024-01-01T00:10:02Z","testClass":"com.B","testMethod":null,"discrete":{"ports":[8080]},"numeric":{}}
        {"type":"snapshot","kind":"FINAL","timestamp":"2024-01-01T00:10:03Z","testClass":null,"testMethod":null,"discrete":{"ports":[8080]},"numeric":{}}
        {"type":"footer","finishedAt":"2024-01-01T00:10:04Z","lifecycles":[{"testClass":"com.B","start":"2024-01-01T00:10:01Z","end":"2024-01-01T00:10:02Z"}]}
        """.trimIndent()

    @Test
    fun `single-report run writes a leak summary next to the input raw report`(
        @TempDir tempDir: Path,
    ) {
        val report = tempDir.resolve("raw.json").toFile().also { it.writeText(standalonePort8080Report) }
        val out = capture()
        val err = capture()

        val exit = AttributionCli.run(arrayOf(report.absolutePath), out.stream, err.stream)

        assertEquals(0, exit)
        val summary = leakSummaryNextTo(report)
        assertTrue(summary != null && summary.exists(), "expected a leak-summary-*.html next to the input")
        val html = summary!!.readText()
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("Network Port Leaks"))
        assertTrue(html.contains("8080"))
        assertTrue(html.contains("com.A"))
        assertTrue(out.text.contains("Wrote leak summary to "), "expected the path to be logged")
    }

    @Test
    fun `two-report run intersects candidate sets across runs`(
        @TempDir tempDir: Path,
    ) {
        val r1 = tempDir.resolve("r1.json").toFile().also { it.writeText(standalonePort8080Report) }
        val r2 = tempDir.resolve("r2.json").toFile().also { it.writeText(secondRunPort8080AttributedToB) }
        val out = capture()

        val exit = AttributionCli.run(arrayOf(r1.absolutePath, r2.absolutePath), out.stream, capture().stream)

        assertEquals(0, exit)
        val summary = leakSummaryNextTo(r1)
        assertTrue(summary != null && summary.exists())
        // Run 1 attributes 8080 to com.A; run 2 attributes 8080 to com.B; intersection is empty,
        // so the union (com.A and com.B) is used and the defect indicator fires.
        val html = summary!!.readText()
        assertTrue(html.contains("8080"))
        assertTrue(html.contains("DEFECT"))
        assertTrue(html.contains("com.A"))
        assertTrue(html.contains("com.B"))
    }

    private fun leakSummaryNextTo(rawReport: File): File? =
        rawReport.parentFile
            ?.listFiles { _, name -> name.startsWith("leak-summary-") && name.endsWith(".html") }
            ?.firstOrNull()

    @Test
    fun `missing input file returns non-zero exit and prints error`(
        @TempDir tempDir: Path,
    ) {
        val missing = tempDir.resolve("nope.json").toFile()
        val err = capture()

        val exit = AttributionCli.run(arrayOf(missing.absolutePath), capture().stream, err.stream)

        assertNotEquals(0, exit)
        assertTrue(err.text.contains("not found"))
    }

    @Test
    fun `no arguments prints usage and exits non-zero`() {
        val err = capture()
        val exit = AttributionCli.run(emptyArray(), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("Usage:"))
    }

    @Test
    fun `help flag prints usage and exits non-zero`() {
        val err = capture()
        val exit = AttributionCli.run(arrayOf("--help"), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("Usage:"))
    }

    @Test
    fun `unknown option exits non-zero`() {
        val err = capture()
        val exit = AttributionCli.run(arrayOf("--made-up", "x"), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("unknown option"))
    }

    @Test
    fun `more than two positional args exits non-zero`(
        @TempDir tempDir: Path,
    ) {
        val r = tempDir.resolve("r.json").toFile().also { it.writeText(standalonePort8080Report) }
        val err = capture()
        val exit = AttributionCli.run(arrayOf(r.absolutePath, r.absolutePath, r.absolutePath), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("expected 1 or 2"))
    }

    @Test
    fun `memory threshold flag suppresses memory leaks below the threshold`(
        @TempDir tempDir: Path,
    ) {
        val mb = 1_048_576L
        val raw =
            """
            {"type":"header","runId":"r","startedAt":"2024-01-01T00:00:00Z","monitors":["memory"],"snapshotGranularity":"class"}
            {"type":"snapshot","kind":"BASELINE","timestamp":"2024-01-01T00:00:00Z","testClass":null,"testMethod":null,"discrete":{},"numeric":{"memory":{"value":"${100 * mb}","timestamp":"2024-01-01T00:00:00Z"}}}
            {"type":"snapshot","kind":"FINAL","timestamp":"2024-01-01T00:00:03Z","testClass":null,"testMethod":null,"discrete":{},"numeric":{"memory":{"value":"${150 * mb}","timestamp":"2024-01-01T00:00:03Z"}}}
            {"type":"footer","finishedAt":"2024-01-01T00:00:04Z","lifecycles":[]}
            """.trimIndent()
        val report = tempDir.resolve("raw.json").toFile().also { it.writeText(raw) }
        val out = capture()

        val exit =
            AttributionCli.run(
                arrayOf(report.absolutePath, "--memory-threshold-mb", "100"),
                out.stream,
                capture().stream,
            )

        assertEquals(0, exit)
        // Growth (50 MB) is below the 100 MB threshold, so no memory leak should be reported.
        assertFalse(out.text.contains("Memory Leaks"), out.text)
    }

    private class CapturedStream(
        val stream: PrintStream,
        private val buffer: ByteArrayOutputStream,
    ) {
        val text: String get() = buffer.toString(Charsets.UTF_8)
    }

    private fun capture(): CapturedStream {
        val buf = ByteArrayOutputStream()
        return CapturedStream(PrintStream(buf, true, Charsets.UTF_8), buf)
    }

    @Suppress("unused")
    private fun File.exists(): Boolean = this.isFile
}
