package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant

class AttributionRunnerTest {
    private val timestamp = "2024-01-01T00-00-00Z"

    private fun config(
        monitored: String = "ports",
        buildFailureTypes: String = "",
        memoryThresholdMb: Long = 0L,
    ): Configuration {
        val sys =
            mapOf(
                "resource.leak.detector.monitored.resource.types" to monitored,
                "resource.leak.detector.build.failure.resource.types" to buildFailureTypes,
                "resource.leak.detector.memory.growth.threshold.mb" to memoryThresholdMb.toString(),
            )
        return Configuration(systemPropertyLookup = { sys[it] })
    }

    private fun reportPaths(outputDir: File): ReportPaths = ReportPaths(outputDir, timestamp)

    private fun writeRawReportWithPortLeak(file: File) {
        val writer = RawReportWriter(file, runId = "r")
        writer.open(Instant.parse("2024-01-01T00:00:00Z"), listOf("ports"), SnapshotGranularity.CLASS)
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.BASELINE,
                timestamp = Instant.parse("2024-01-01T00:00:00Z"),
                testClass = null,
                testMethod = null,
                discrete = mapOf(ResourceType.PORTS to emptySet()),
                numeric = emptyMap(),
            ),
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.BEFORE_ALL,
                timestamp = Instant.parse("2024-01-01T00:00:01Z"),
                testClass = "com.A",
                testMethod = null,
                discrete = mapOf(ResourceType.PORTS to emptySet()),
                numeric = emptyMap(),
            ),
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.AFTER_ALL,
                timestamp = Instant.parse("2024-01-01T00:00:02Z"),
                testClass = "com.A",
                testMethod = null,
                discrete = mapOf(ResourceType.PORTS to setOf(ResourceId.PortId(8080))),
                numeric = emptyMap(),
            ),
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.FINAL,
                timestamp = Instant.parse("2024-01-01T00:00:03Z"),
                testClass = null,
                testMethod = null,
                discrete = mapOf(ResourceType.PORTS to setOf(ResourceId.PortId(8080))),
                numeric = emptyMap(),
            ),
        )
        writer.closeWith(
            finishedAt = Instant.parse("2024-01-01T00:00:04Z"),
            lifecycles =
                mapOf(
                    "com.A" to
                        TestClassLifecycle(
                            Instant.parse("2024-01-01T00:00:01Z"),
                            Instant.parse("2024-01-01T00:00:02Z"),
                        ),
                ),
        )
    }

    @Test
    fun `runInline writes leak-summary HTML alongside the raw report`(
        @TempDir tempDir: Path,
    ) {
        val paths = reportPaths(tempDir.toFile())
        writeRawReportWithPortLeak(paths.rawReport)

        AttributionRunner(reportPaths = paths, configuration = config()).runInline()

        assertTrue(paths.leakSummary.exists(), "leak-summary file should be written next to raw report")
        val html = paths.leakSummary.readText()
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("Network Port Leaks"))
        assertTrue(html.contains("8080"))
        assertTrue(html.contains("com.A"))
    }

    @Test
    fun `runInline triggers build failure when leak resource type is in build-failure list`(
        @TempDir tempDir: Path,
    ) {
        val paths = reportPaths(tempDir.toFile())
        writeRawReportWithPortLeak(paths.rawReport)

        var triggered = false
        AttributionRunner(
            reportPaths = paths,
            configuration = config(buildFailureTypes = "ports"),
            buildFailureAction = { triggered = true },
        ).runInline()

        assertTrue(triggered)
    }

    @Test
    fun `runInline does not trigger build failure when leak type is not in build-failure list`(
        @TempDir tempDir: Path,
    ) {
        val paths = reportPaths(tempDir.toFile())
        writeRawReportWithPortLeak(paths.rawReport)

        var triggered = false
        AttributionRunner(
            reportPaths = paths,
            configuration = config(buildFailureTypes = "memory"),
            buildFailureAction = { triggered = true },
        ).runInline()

        assertFalse(triggered)
    }

    @Test
    fun `runInline is a no-op when raw report file is missing`(
        @TempDir tempDir: Path,
    ) {
        val paths = reportPaths(tempDir.toFile())
        // No raw report written.
        var triggered = false
        AttributionRunner(
            reportPaths = paths,
            configuration = config(buildFailureTypes = "ports"),
            buildFailureAction = { triggered = true },
        ).runInline()
        assertFalse(triggered)
        assertFalse(paths.leakSummary.exists())
    }
}
