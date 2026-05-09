package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemPropertyMonitorTest {
    private val monitor = SystemPropertyMonitor()

    @AfterEach
    fun cleanup() {
        System.clearProperty("test.property.1")
        System.clearProperty("test.property.2")
    }

    @Test
    fun `snapshot returns all system properties`() {
        val existingProperties = System.getProperties().stringPropertyNames()
        val resources = monitor.snapshot()

        assertEquals(existingProperties.size, resources.size)
        assertTrue(resources.all { it is ResourceId.PropertyId })
        existingProperties.forEach { propName ->
            assertTrue(resources.contains(ResourceId.PropertyId(propName)))
        }
    }

    @Test
    fun `snapshot includes newly set properties`() {
        val beforeCount = monitor.snapshot().size

        System.setProperty("test.property.1", "value1")
        val afterCount = monitor.snapshot().size

        assertEquals(beforeCount + 1, afterCount)
        assertTrue(monitor.snapshot().contains(ResourceId.PropertyId("test.property.1")))
    }

    @Test
    fun `snapshot excludes cleared properties`() {
        System.setProperty("test.property.1", "value1")
        System.setProperty("test.property.2", "value2")
        val withProperties = monitor.snapshot()

        System.clearProperty("test.property.1")
        val afterClear = monitor.snapshot()

        assertTrue(withProperties.contains(ResourceId.PropertyId("test.property.1")))
        assertTrue(!afterClear.contains(ResourceId.PropertyId("test.property.1")))
        assertTrue(afterClear.contains(ResourceId.PropertyId("test.property.2")))
    }
}
