package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.Properties

class AttributionRunnerTest {
    private fun configWith(
        rawReportPath: String,
        monitored: String = "ports",
        buildFailureTypes: String = "",
        memoryThresholdMb: Long = 0L,
    ): Configuration {
        val props =
            Properties().apply {
                setProperty("monitored.resource.types", monitored)
                setProperty("raw.report.output.path", rawReportPath)
                setProperty("build.failure.resource.types", buildFailureTypes)
                setProperty("memory.growth.threshold.mb", memoryThresholdMb.toString())
            }
        return Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
    }

    private fun writeRawReportWithPortLeak(file: File) {
        val writer = RawReportWriter(file, runId = "r")
        writer.open(Instant.parse("2024-01-01T00:00:00Z"), listOf("ports"), SnapshotGranularity.CLASS)
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.BASELINE,
                timestamp = Instant.parse("2024-01-01T00:00:00Z"),
                testClass = null,
                testMethod = null,
                discrete = mapOf(ResourceId.PortId::class to emptySet()),
                numeric = emptyMap(),
            ),
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.BEFORE_ALL,
                timestamp = Instant.parse("2024-01-01T00:00:01Z"),
                testClass = "com.A",
                testMethod = null,
                discrete = mapOf(ResourceId.PortId::class to emptySet()),
                numeric = emptyMap(),
            ),
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.AFTER_ALL,
                timestamp = Instant.parse("2024-01-01T00:00:02Z"),
                testClass = "com.A",
                testMethod = null,
                discrete = mapOf(ResourceId.PortId::class to setOf(ResourceId.PortId(8080))),
                numeric = emptyMap(),
            ),
        )
        writer.appendSnapshot(
            Snapshot(
                kind = SnapshotKind.FINAL,
                timestamp = Instant.parse("2024-01-01T00:00:03Z"),
                testClass = null,
                testMethod = null,
                discrete = mapOf(ResourceId.PortId::class to setOf(ResourceId.PortId(8080))),
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
    fun `runInline writes leak-report text alongside the raw report`(
        @TempDir tempDir: Path,
    ) {
        val rawPath = tempDir.resolve("raw.json").toFile()
        writeRawReportWithPortLeak(rawPath)

        AttributionRunner(configuration = configWith(rawPath.absolutePath)).runInline()

        val textFile = tempDir.resolve("leak-report.txt").toFile()
        assertTrue(textFile.exists(), "leak-report.txt should be written next to raw.json")
        val text = textFile.readText()
        assertTrue(text.contains("Network Port Leaks:"))
        assertTrue(text.contains("Port: 8080"))
        assertTrue(text.contains("com.A"))
    }

    @Test
    fun `runInline triggers build failure when leak resource type is in build-failure list`(
        @TempDir tempDir: Path,
    ) {
        val rawPath = tempDir.resolve("raw.json").toFile()
        writeRawReportWithPortLeak(rawPath)

        var triggered = false
        AttributionRunner(
            configuration = configWith(rawPath.absolutePath, buildFailureTypes = "ports"),
            buildFailureAction = { triggered = true },
        ).runInline()

        assertTrue(triggered)
    }

    @Test
    fun `runInline does not trigger build failure when leak type is not in build-failure list`(
        @TempDir tempDir: Path,
    ) {
        val rawPath = tempDir.resolve("raw.json").toFile()
        writeRawReportWithPortLeak(rawPath)

        var triggered = false
        AttributionRunner(
            configuration = configWith(rawPath.absolutePath, buildFailureTypes = "memory"),
            buildFailureAction = { triggered = true },
        ).runInline()

        assertFalse(triggered)
    }

    @Test
    fun `runInline is a no-op when raw report file is missing`(
        @TempDir tempDir: Path,
    ) {
        val rawPath = tempDir.resolve("missing.json").toFile()
        var triggered = false
        AttributionRunner(
            configuration = configWith(rawPath.absolutePath, buildFailureTypes = "ports"),
            buildFailureAction = { triggered = true },
        ).runInline()
        assertFalse(triggered)
        assertFalse(tempDir.resolve("leak-report.txt").toFile().exists())
    }
}
