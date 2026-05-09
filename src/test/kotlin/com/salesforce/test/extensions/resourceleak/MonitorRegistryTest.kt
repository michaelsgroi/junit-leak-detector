package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.TestClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

        val baselineProp = state.getAllDiscreteResources()[ResourceId.PropertyId("test.property.baseline")]
        assertTrue(baselineProp != null)
        assertEquals(true, baselineProp?.isBaseline)
    }

    @Test
    fun `snapshotAll detects new discrete resources after baseline`() {
        val state = ResourceState()
        val clock = TestClock(0L)
        val registry = MonitorRegistry(state, clock, configuration = configWith("systemprops"))
        registry.captureBaseline()

        clock.advanceMillis(5)
        System.setProperty("test.property.leaked", "v")
        registry.snapshotAll()

        val leaked = state.getAllDiscreteResources()[ResourceId.PropertyId("test.property.leaked")]
        assertTrue(leaked != null)
        assertEquals(false, leaked?.isBaseline)
    }

    @Test
    fun `snapshotAll records numeric measurements`() {
        val state = ResourceState()
        val clock = TestClock(0L)
        val registry = MonitorRegistry(state, clock, configuration = configWith("memory"))
        registry.captureBaseline()
        clock.advanceMillis(5)
        registry.snapshotAll()

        val measurements = state.getAllNumericMeasurements()
        assertTrue(measurements.size >= 2)
    }

    @Test
    fun `construction fails fast when ddbtables monitor configured but AWS SDK absent`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            MonitorRegistry(
                ResourceState(),
                TestClock(0L),
                classPresent = { false },
                configuration = configWith("ddbtables")
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
            configuration = configWith("ddbtables")
        )
    }

    @Test
    fun `classpath check is not invoked for monitors without optional dependencies`() {
        MonitorRegistry(
            ResourceState(),
            TestClock(0L),
            classPresent = { false },
            configuration = configWith("systemprops,memory,threads")
        )
    }

    @Test
    fun `hasAny returns false when no resource types configured`() {
        val registry = MonitorRegistry(
            ResourceState(),
            TestClock(0L),
            configuration = configWith("")
        )
        assertEquals(false, registry.hasAny())
    }
}
