package com.michaelsgroi.test.extensions.resourceleak

import com.michaelsgroi.test.extensions.LogAssertingExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ForkDetectorTest {
    @RegisterExtension
    val logExt = LogAssertingExtension(LogAssertingExtension.Level.WARN)

    @Test
    fun `writes a marker for the current fork`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile()
        val now = Instant.parse("2024-01-01T12:00:00Z")
        val detector = ForkDetector(markerDirectory = dir, clock = fixedClock(now), pid = 1234L)

        detector.checkAndRecordFork()

        val marker = File(dir, "1234.marker")
        assertTrue(marker.exists())
        val text = marker.readText()
        assertTrue(text.contains("pid=1234"))
        assertTrue(text.contains("startedAt=2024-01-01T12:00:00Z"))
    }

    @Test
    fun `no warning when no prior markers exist`(
        @TempDir tempDir: Path,
    ) {
        val detector = ForkDetector(markerDirectory = tempDir.toFile(), pid = 1234L)
        val priors = detector.checkAndRecordFork()
        assertEquals(0, priors.size)
        assertFalse(logExt.logged(LogAssertingExtension.Level.WARN) { it.contains("Multiple forks") })
    }

    @Test
    fun `warns when a prior marker is within the detection window`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile()
        val now = Instant.parse("2024-01-01T12:00:00Z")
        // Plant a prior marker 30 seconds ago.
        File(dir, "999.marker").writeText("pid=999\nstartedAt=${now.minusSeconds(30)}\n")

        val priors =
            ForkDetector(
                markerDirectory = dir,
                clock = fixedClock(now),
                pid = 1234L,
            ).checkAndRecordFork()

        assertEquals(1, priors.size)
        assertEquals(999L, priors[0].pid)
        assertTrue(logExt.logged(LogAssertingExtension.Level.WARN) { it.contains("Multiple forks") })
    }

    @Test
    fun `ignores prior markers older than the detection window`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile()
        val now = Instant.parse("2024-01-01T12:00:00Z")
        // Plant a prior marker 1 hour ago - well outside the 5-minute default window.
        File(dir, "999.marker").writeText("pid=999\nstartedAt=${now.minusSeconds(3600)}\n")

        val priors =
            ForkDetector(
                markerDirectory = dir,
                clock = fixedClock(now),
                pid = 1234L,
            ).checkAndRecordFork()

        assertEquals(0, priors.size)
        assertFalse(logExt.logged(LogAssertingExtension.Level.WARN) { it.contains("Multiple forks") })
    }

    @Test
    fun `ignores marker for the current pid`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile()
        val now = Instant.parse("2024-01-01T12:00:00Z")
        // Plant a marker with the same PID as the current detector.
        File(dir, "1234.marker").writeText("pid=1234\nstartedAt=${now.minusSeconds(10)}\n")

        val priors =
            ForkDetector(
                markerDirectory = dir,
                clock = fixedClock(now),
                pid = 1234L,
            ).checkAndRecordFork()

        assertEquals(0, priors.size)
    }

    @Test
    fun `tolerates malformed marker files`(
        @TempDir tempDir: Path,
    ) {
        val dir = tempDir.toFile()
        File(dir, "garbage.marker").writeText("not a valid marker")
        File(dir, "999.marker").writeText("pid=999\nstartedAt=${Instant.now().minusSeconds(30)}\n")

        val priors =
            ForkDetector(
                markerDirectory = dir,
                priorMarkerWindow = Duration.ofMinutes(5),
                pid = 1234L,
            ).checkAndRecordFork()

        // Garbage marker silently skipped; valid prior still detected.
        assertEquals(1, priors.size)
    }

    @Test
    fun `creates marker directory if missing`(
        @TempDir tempDir: Path,
    ) {
        val nestedDir = File(tempDir.toFile(), "a/b/c/forks")
        ForkDetector(markerDirectory = nestedDir, pid = 1234L).checkAndRecordFork()
        assertTrue(nestedDir.exists())
        assertTrue(File(nestedDir, "1234.marker").exists())
    }

    private fun fixedClock(at: Instant): Clock = Clock.fixed(at, ZoneOffset.UTC)
}
