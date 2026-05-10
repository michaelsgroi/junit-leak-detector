package com.michaelsgroi.test.leakdetector.orchestrator

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

class OrchestratorMainTest {
    @Test
    fun `no arguments prints usage and exits non-zero`() {
        val err = capture()
        val exit = OrchestratorMain.run(emptyArray(), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("--project-root is required"))
    }

    @Test
    fun `help flag prints usage and exits non-zero`() {
        val err = capture()
        val exit = OrchestratorMain.run(arrayOf("--help"), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("Usage:"))
    }

    @Test
    fun `unknown option exits non-zero`(
        @TempDir tempDir: Path,
    ) {
        val err = capture()
        val exit = OrchestratorMain.run(arrayOf("--bogus", "x"), capture().stream, err.stream)
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("unknown option"))
    }

    @Test
    fun `non-existent project root exits non-zero`(
        @TempDir tempDir: Path,
    ) {
        val missing = tempDir.resolve("nope").toFile()
        val err = capture()
        val exit =
            OrchestratorMain.run(
                arrayOf("--project-root", missing.absolutePath),
                capture().stream,
                err.stream,
            )
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("must be a Maven module"))
    }

    @Test
    fun `project root without pom exits non-zero`(
        @TempDir tempDir: Path,
    ) {
        // Directory exists but has no pom.xml.
        val err = capture()
        val exit =
            OrchestratorMain.run(
                arrayOf("--project-root", tempDir.toFile().absolutePath),
                capture().stream,
                err.stream,
            )
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("must be a Maven module"))
    }

    @Test
    fun `runs flag rejects values outside 1 to 2`(
        @TempDir tempDir: Path,
    ) {
        val moduleDir = tempDir.toFile().also { java.io.File(it, "pom.xml").writeText("<project/>") }
        val err = capture()
        val exit =
            OrchestratorMain.run(
                arrayOf("--project-root", moduleDir.absolutePath, "--runs", "3"),
                capture().stream,
                err.stream,
            )
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("--runs must be 1 or 2"))
    }

    @Test
    fun `runs flag rejects non-numeric values`(
        @TempDir tempDir: Path,
    ) {
        val moduleDir = tempDir.toFile().also { java.io.File(it, "pom.xml").writeText("<project/>") }
        val err = capture()
        val exit =
            OrchestratorMain.run(
                arrayOf("--project-root", moduleDir.absolutePath, "--runs", "abc"),
                capture().stream,
                err.stream,
            )
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("--runs must be 1 or 2"))
    }

    @Test
    fun `seed flag rejects non-numeric values`(
        @TempDir tempDir: Path,
    ) {
        val moduleDir = tempDir.toFile().also { java.io.File(it, "pom.xml").writeText("<project/>") }
        val err = capture()
        val exit =
            OrchestratorMain.run(
                arrayOf("--project-root", moduleDir.absolutePath, "--seed", "notanumber"),
                capture().stream,
                err.stream,
            )
        assertNotEquals(0, exit)
        assertTrue(err.text.contains("--seed must be a number"))
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
}
