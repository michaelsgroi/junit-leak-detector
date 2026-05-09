package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant

class RawReportWriterTest {
    @Test
    fun `writes header snapshot lines and footer in order with one record per line`(
        @TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("nested/raw-report.json").toFile()
        val writer = RawReportWriter(outputFile, runId = "run-1")
        val started = Instant.parse("2024-01-01T00:00:00Z")
        val ts = Instant.parse("2024-01-01T00:00:01Z")
        val finished = Instant.parse("2024-01-01T00:00:10Z")

        writer.open(
            startedAt = started,
            monitors = listOf("threads"),
            snapshotGranularity = SnapshotGranularity.CLASS,
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.BASELINE,
                timestamp = ts,
                testClass = null,
                testMethod = null,
                discrete = mapOf(ResourceId.ThreadId::class to setOf(ResourceId.ThreadId("main", 1L))),
                numeric = emptyMap(),
            ),
        )
        writer.closeWith(
            finishedAt = finished,
            lifecycles = mapOf("com.A" to TestClassLifecycle(ts, finished)),
        )

        assertTrue(outputFile.exists(), "raw report file should be created")
        val lines = outputFile.readLines()
        assertEquals(3, lines.size, "expected header + 1 snapshot + footer")
        assertTrue(lines[0].contains(""""type":"header""""))
        assertTrue(lines[0].contains(""""runId":"run-1""""))
        assertTrue(lines[1].contains(""""type":"snapshot""""))
        assertTrue(lines[1].contains(""""kind":"BASELINE""""))
        assertTrue(lines[2].contains(""""type":"footer""""))
        assertTrue(lines[2].contains("com.A"))
    }

    @Test
    fun `appendSnapshot is a no-op before open and after close`(
        @TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("raw-report.json").toFile()
        val writer = RawReportWriter(outputFile, runId = "r")

        // Before open: should not throw or create the file.
        writer.appendSnapshot(
            Snapshot(SnapshotKind.BASELINE, Instant.EPOCH, null, null, emptyMap(), emptyMap()),
        )
        assertEquals(false, outputFile.exists())

        writer.open(Instant.EPOCH, emptyList(), SnapshotGranularity.CLASS)
        writer.closeWith(Instant.EPOCH, emptyMap())

        // After close: appending should not reopen the file.
        val sizeAfterClose = outputFile.length()
        writer.appendSnapshot(
            Snapshot(SnapshotKind.FINAL, Instant.EPOCH, null, null, emptyMap(), emptyMap()),
        )
        assertEquals(sizeAfterClose, outputFile.length())
    }

    @Test
    fun `creates parent directories if missing`(
        @TempDir tempDir: Path,
    ) {
        val nested: File = tempDir.resolve("a/b/c/raw.json").toFile()
        val writer = RawReportWriter(nested)
        writer.open(Instant.EPOCH, emptyList(), SnapshotGranularity.CLASS)
        writer.closeWith(Instant.EPOCH, emptyMap())
        assertTrue(nested.exists())
    }
}
