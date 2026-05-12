package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RawReportJsonTest {
    @Test
    fun `header encodes runId monitors granularity and memory threshold`() {
        val line =
            RawReportJson.encodeHeader(
                runId = "abc",
                startedAt = "2024-01-01T00:00:00Z",
                monitors = listOf("threads", "ports"),
                snapshotGranularity = "class",
                memoryGrowthThresholdBytes = 52428800L,
            )
        assertEquals(
            """{"type":"header","runId":"abc","startedAt":"2024-01-01T00:00:00Z","monitors":["threads","ports"],"snapshotGranularity":"class","memoryGrowthThresholdBytes":"52428800"}""",
            line,
        )
    }

    @Test
    fun `footer encodes finishedAt and lifecycles`() {
        val line =
            RawReportJson.encodeFooter(
                finishedAt = "2024-01-01T00:01:00Z",
                lifecycles =
                    mapOf(
                        "com.A" to TestClassLifecycle(Instant.parse("2024-01-01T00:00:01Z"), Instant.parse("2024-01-01T00:00:02Z")),
                    ),
            )
        assertEquals(
            """{"type":"footer","finishedAt":"2024-01-01T00:01:00Z","lifecycles":[{"testClass":"com.A","start":"2024-01-01T00:00:01Z","end":"2024-01-01T00:00:02Z"}]}""",
            line,
        )
    }

    @Test
    fun `snapshot encodes kind timestamp test context discrete and numeric`() {
        val ts = Instant.parse("2024-01-01T00:00:05Z")
        val line =
            RawReportJson.encodeSnapshot(
                Snapshot(
                    kind = SnapshotKind.BEFORE_ALL,
                    timestamp = ts,
                    testClass = "com.A",
                    testMethod = null,
                    discrete =
                        mapOf(
                            ResourceType.PORTS to setOf(ResourceId.PortId(8080)),
                            ResourceType.SYSTEM_PROPS to setOf(ResourceId.PropertyId("k")),
                        ),
                    numeric = mapOf("memory" to NumericResourceMeasurement(1024L, ts)),
                ),
            )
        assertTrue(line.contains(""""kind":"BEFORE_ALL""""), "actual: $line")
        assertTrue(line.contains(""""timestamp":"2024-01-01T00:00:05Z""""), "actual: $line")
        assertTrue(line.contains(""""testClass":"com.A""""), "actual: $line")
        assertTrue(line.contains(""""testMethod":null"""), "actual: $line")
        assertTrue(line.contains(""""ports":[8080]"""), "actual: $line")
        assertTrue(line.contains(""""systemprops":["k"]"""), "actual: $line")
        assertTrue(line.contains(""""memory":{"value":"1024","timestamp":"2024-01-01T00:00:05Z"}"""), "actual: $line")
    }

    @Test
    fun `thread id encoded as object with name and id`() {
        val ts = Instant.parse("2024-01-01T00:00:00Z")
        val line =
            RawReportJson.encodeSnapshot(
                Snapshot(
                    kind = SnapshotKind.AFTER_ALL,
                    timestamp = ts,
                    testClass = null,
                    testMethod = null,
                    discrete =
                        mapOf(
                            ResourceType.THREADS to setOf(ResourceId.ThreadId("worker", 42L)),
                        ),
                    numeric = emptyMap(),
                ),
            )
        assertTrue(line.contains(""""threads":[{"name":"worker","id":"42"}]"""))
    }

    @Test
    fun `string escaping handles quotes backslashes and control chars`() {
        val ts = Instant.parse("2024-01-01T00:00:00Z")
        val line =
            RawReportJson.encodeSnapshot(
                Snapshot(
                    kind = SnapshotKind.BASELINE,
                    timestamp = ts,
                    testClass = "com.A\"\\\nB",
                    testMethod = null,
                    discrete = emptyMap(),
                    numeric = emptyMap(),
                ),
            )
        assertTrue(line.contains("""com.A\"\\\nB"""))
    }
}
