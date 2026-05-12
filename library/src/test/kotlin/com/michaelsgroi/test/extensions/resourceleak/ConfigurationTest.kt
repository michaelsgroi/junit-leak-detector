package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigurationTest {
    @Test
    fun `defaults apply when no system property is set`() {
        val config = Configuration(systemPropertyLookup = { null })
        assertEquals(Configuration.DEFAULT_MONITORED_RESOURCE_TYPES, config.monitoredResourceTypes)
        assertEquals(10L, config.threadGracePeriodSeconds)
        assertEquals(50L, config.memoryGrowthThresholdMb)
        assertEquals("", config.buildFailureResourceTypes)
        assertEquals(SnapshotGranularity.CLASS, config.snapshotGranularity)
        assertEquals("target/resource-leak-detector", config.reportOutputDir)
        assertFalse(config.disabled)
    }

    @Test
    fun `system property overrides default`() {
        val sys = mapOf("resource.leak.detector.monitored.resource.types" to "ports,threads")
        val config = Configuration(systemPropertyLookup = { sys[it] })
        assertEquals("ports,threads", config.monitoredResourceTypes)
    }

    @Test
    fun `snapshot granularity reads from system property`() {
        val sys = mapOf("resource.leak.detector.snapshot.granularity" to "test")
        val config = Configuration(systemPropertyLookup = { sys[it] })
        assertEquals(SnapshotGranularity.TEST, config.snapshotGranularity)
    }

    @Test
    fun `snapshot granularity falls back to default for unknown value`() {
        val sys = mapOf("resource.leak.detector.snapshot.granularity" to "garbage")
        val config = Configuration(systemPropertyLookup = { sys[it] })
        assertEquals(SnapshotGranularity.CLASS, config.snapshotGranularity)
    }

    @Test
    fun `blank system property is treated as unset`() {
        val config = Configuration(systemPropertyLookup = { "" })
        assertEquals(10L, config.threadGracePeriodSeconds)
    }

    @Test
    fun `disabled flag reads boolean strictly`() {
        assertTrue(
            Configuration(
                systemPropertyLookup = { mapOf("resource.leak.detector.disabled" to "true")[it] },
            ).disabled,
        )
        assertFalse(
            Configuration(
                systemPropertyLookup = { mapOf("resource.leak.detector.disabled" to "false")[it] },
            ).disabled,
        )
        assertFalse(
            Configuration(
                systemPropertyLookup = { mapOf("resource.leak.detector.disabled" to "garbage")[it] },
            ).disabled,
        )
    }
}
