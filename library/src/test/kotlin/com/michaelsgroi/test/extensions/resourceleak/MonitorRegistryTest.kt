package com.michaelsgroi.test.extensions.resourceleak

import com.michaelsgroi.test.TestClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class MonitorRegistryTest {
    @AfterEach
    fun cleanup() {
        System.clearProperty("test.property.baseline")
        System.clearProperty("test.property.leaked")
    }

    private fun configWith(monitoredTypes: String): Configuration {
        val props = Properties().apply { setProperty("monitored.resource.types", monitoredTypes) }
        return Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
    }

    @Test
    fun `captureBaseline records baseline discrete resources`() {
        System.setProperty("test.property.baseline", "v")
        val state = ResourceState()
        val clock = TestClock(0L)
        val registry = MonitorRegistry(state, clock, configuration = configWith("systemprops"))

        registry.captureBaseline()

        val baseline = state.getBaselineDiscrete(ResourceType.SYSTEM_PROPS)
        assertTrue(baseline.contains(ResourceId.PropertyId("test.property.baseline")))
        assertEquals(baseline, state.getCurrentDiscrete(ResourceType.SYSTEM_PROPS))
    }

    @Test
    fun `snapshotAll updates current with new resources, leaving baseline unchanged`() {
        val state = ResourceState()
        val clock = TestClock(0L)
        val registry = MonitorRegistry(state, clock, configuration = configWith("systemprops"))
        registry.captureBaseline()
        val baseline = state.getBaselineDiscrete(ResourceType.SYSTEM_PROPS)

        clock.advanceMillis(5)
        System.setProperty("test.property.leaked", "v")
        registry.snapshotAll()

        val current = state.getCurrentDiscrete(ResourceType.SYSTEM_PROPS)
        assertTrue(current.contains(ResourceId.PropertyId("test.property.leaked")))
        assertFalse(baseline.contains(ResourceId.PropertyId("test.property.leaked")))
        assertEquals(baseline, state.getBaselineDiscrete(ResourceType.SYSTEM_PROPS))
    }

    @Test
    fun `snapshotAll updates current numeric measurement`() {
        val state = ResourceState()
        val clock = TestClock(0L)
        val registry = MonitorRegistry(state, clock, configuration = configWith("memory"))
        registry.captureBaseline()
        val baseline = state.getBaselineNumeric()
        clock.advanceMillis(5)
        registry.snapshotAll()

        assertTrue(state.getCurrentNumeric() != null)
        assertTrue(baseline != null)
    }

    @Test
    fun `construction fails fast when ddbtables monitor configured but AWS SDK absent`() {
        val ex =
            assertThrows(IllegalStateException::class.java) {
                MonitorRegistry(
                    ResourceState(),
                    TestClock(0L),
                    classPresent = { false },
                    configuration = configWith("ddbtables"),
                )
            }
        val message = ex.message ?: ""
        assertTrue(message.contains("ddbtables"))
        assertTrue(message.contains("software.amazon.awssdk:dynamodb"))
    }

    @Test
    fun `construction succeeds when ddbtables monitor configured and AWS SDK present`() {
        MonitorRegistry(
            ResourceState(),
            TestClock(0L),
            classPresent = { true },
            configuration = configWith("ddbtables"),
        )
    }

    @Test
    fun `classpath check is not invoked for monitors without optional dependencies`() {
        MonitorRegistry(
            ResourceState(),
            TestClock(0L),
            classPresent = { false },
            configuration = configWith("systemprops,memory,threads"),
        )
    }

    @Test
    fun `captureBaseline and snapshotAll emit records to the raw report writer`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path,
    ) {
        val outputFile = tempDir.resolve("raw.json").toFile()
        val writer = RawReportWriter(outputFile, runId = "r")
        writer.open(java.time.Instant.EPOCH, listOf("systemprops"), SnapshotGranularity.CLASS)
        val state = ResourceState()
        val clock = TestClock(0L)
        val registry =
            MonitorRegistry(
                resourceState = state,
                clock = clock,
                configuration = configWith("systemprops"),
                rawReportWriter = writer,
            )

        registry.captureBaseline()
        registry.snapshotAll(kind = SnapshotKind.BEFORE_ALL, testClass = "com.A")
        writer.closeWith(java.time.Instant.EPOCH, emptyMap())

        val lines = outputFile.readLines()
        // header + baseline + before_all + footer
        assertEquals(4, lines.size)
        assertTrue(lines[1].contains(""""kind":"BASELINE""""))
        assertTrue(lines[2].contains(""""kind":"BEFORE_ALL""""))
        assertTrue(lines[2].contains(""""testClass":"com.A""""))
    }

    @Test
    fun `hasAny returns false when no resource types configured`() {
        val registry =
            MonitorRegistry(
                ResourceState(),
                TestClock(0L),
                configuration = configWith(""),
            )
        assertEquals(false, registry.hasAny())
    }
}
