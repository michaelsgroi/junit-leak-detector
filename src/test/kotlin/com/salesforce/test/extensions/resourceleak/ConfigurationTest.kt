package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class ConfigurationTest {

    @Test
    fun `defaults apply when neither file nor system property is set`() {
        val config = Configuration(propertiesLoader = { null }, systemPropertyLookup = { null })
        assertEquals("", config.monitoredResourceTypes)
        assertEquals(5000L, config.pollingIntervalMs)
        assertEquals(10L, config.threadGracePeriodSeconds)
        assertEquals(1024L, config.memoryGrowthThresholdMb)
        assertEquals("", config.buildFailureResourceTypes)
    }

    @Test
    fun `properties file values are used when present`() {
        val props = Properties().apply {
            setProperty("monitored.resource.types", "ports,threads")
            setProperty("polling.interval.milliseconds", "200")
            setProperty("thread.grace.period.seconds", "5")
            setProperty("memory.growth.threshold.mb", "50")
            setProperty("build.failure.resource.types", "memory")
        }
        val config = Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
        assertEquals("ports,threads", config.monitoredResourceTypes)
        assertEquals(200L, config.pollingIntervalMs)
        assertEquals(5L, config.threadGracePeriodSeconds)
        assertEquals(50L, config.memoryGrowthThresholdMb)
        assertEquals("memory", config.buildFailureResourceTypes)
    }

    @Test
    fun `system property overrides file value`() {
        val props = Properties().apply { setProperty("monitored.resource.types", "ports") }
        val systemProps = mapOf(
            "resource.leak.detector.monitored.resource.types" to "threads,memory"
        )
        val config = Configuration(
            propertiesLoader = { props },
            systemPropertyLookup = { systemProps[it] }
        )
        assertEquals("threads,memory", config.monitoredResourceTypes)
    }

    @Test
    fun `blank system property does not override file value`() {
        val props = Properties().apply { setProperty("polling.interval.milliseconds", "750") }
        val config = Configuration(
            propertiesLoader = { props },
            systemPropertyLookup = { "" }
        )
        assertEquals(750L, config.pollingIntervalMs)
    }

    @Test
    fun `system property used when file absent`() {
        val systemProps = mapOf(
            "resource.leak.detector.thread.grace.period.seconds" to "30"
        )
        val config = Configuration(
            propertiesLoader = { null },
            systemPropertyLookup = { systemProps[it] }
        )
        assertEquals(30L, config.threadGracePeriodSeconds)
    }
}
