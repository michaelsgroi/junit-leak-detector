package com.salesforce.test.leakdetector.attribution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.time.Instant

class RawReportReaderTest {
    @Test
    fun `reads header snapshots and footer in JSON Lines format`() {
        val raw =
            """
            {"type":"header","runId":"r","startedAt":"2024-01-01T00:00:00Z","monitors":["ports","threads"],"snapshotGranularity":"class"}
            {"type":"snapshot","kind":"BASELINE","timestamp":"2024-01-01T00:00:01Z","testClass":null,"testMethod":null,"discrete":{"ports":[8080],"threads":[{"name":"main","id":"1"}],"systemprops":["k"]},"numeric":{"memory":{"value":"1024","timestamp":"2024-01-01T00:00:01Z"}}}
            {"type":"footer","finishedAt":"2024-01-01T00:00:10Z","lifecycles":[{"testClass":"com.A","start":"2024-01-01T00:00:02Z","end":"2024-01-01T00:00:03Z"}]}
            """.trimIndent()

        val report = RawReportReader.read(StringReader(raw))

        assertEquals("r", report.header.runId)
        assertEquals(listOf("ports", "threads"), report.header.monitors)
        assertEquals("class", report.header.snapshotGranularity)

        assertEquals(1, report.snapshots.size)
        val snap = report.snapshots[0]
        assertEquals("BASELINE", snap.kind)
        assertEquals(Instant.parse("2024-01-01T00:00:01Z"), snap.timestamp)
        assertEquals(listOf(DiscreteResource.Port(8080)), snap.discrete["ports"])
        assertEquals(listOf(DiscreteResource.ThreadResource("main", "1")), snap.discrete["threads"])
        assertEquals(listOf(DiscreteResource.Simple("k")), snap.discrete["systemprops"])
        assertEquals(1024L, snap.numeric["memory"]?.value)

        assertEquals(1, report.footer.lifecycles.size)
        val lc = report.footer.lifecycles[0]
        assertEquals("com.A", lc.testClass)
        assertEquals(Instant.parse("2024-01-01T00:00:02Z"), lc.start)
        assertEquals(Instant.parse("2024-01-01T00:00:03Z"), lc.end)
    }

    @Test
    fun `tolerates blank lines between records`() {
        val raw =
            """
            {"type":"header","runId":"r","startedAt":"2024-01-01T00:00:00Z","monitors":[],"snapshotGranularity":"class"}

            {"type":"footer","finishedAt":"2024-01-01T00:00:10Z","lifecycles":[]}
            """.trimIndent()
        val report = RawReportReader.read(StringReader(raw))
        assertEquals(0, report.snapshots.size)
    }

    @Test
    fun `fails when header is missing`() {
        val raw = """{"type":"footer","finishedAt":"2024-01-01T00:00:10Z","lifecycles":[]}"""
        val ex =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                RawReportReader.read(StringReader(raw))
            }
        assertEquals(true, ex.message!!.contains("header"))
    }

    @Test
    fun `fails when footer is missing`() {
        val raw = """{"type":"header","runId":"r","startedAt":"2024-01-01T00:00:00Z","monitors":[],"snapshotGranularity":"class"}"""
        val ex =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                RawReportReader.read(StringReader(raw))
            }
        assertEquals(true, ex.message!!.contains("footer"))
    }
}
