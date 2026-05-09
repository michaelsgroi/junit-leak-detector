package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentVariableMonitorTest {

    @Test
    fun `gatherResources returns current environment variables`() {
        val monitor = EnvironmentVariableMonitor()

        val resources = monitor.gatherResources()

        assertTrue(resources.contains(ResourceId.EnvironmentVariableId("PATH")))
    }

    @Test
    fun `gatherResources returns EnvironmentVariableId types`() {
        val monitor = EnvironmentVariableMonitor()

        val resources = monitor.gatherResources()

        assertTrue(resources.isNotEmpty())
        assertTrue(resources.all { it is ResourceId.EnvironmentVariableId })
    }
}
