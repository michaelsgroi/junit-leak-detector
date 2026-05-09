package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentVariableMonitorTest {
    @Test
    fun `snapshot returns current environment variables`() {
        val monitor = EnvironmentVariableMonitor()

        val resources = monitor.snapshot()

        assertTrue(resources.contains(ResourceId.EnvironmentVariableId("PATH")))
    }

    @Test
    fun `snapshot returns EnvironmentVariableId types`() {
        val monitor = EnvironmentVariableMonitor()

        val resources = monitor.snapshot()

        assertTrue(resources.isNotEmpty())
        assertTrue(resources.all { it is ResourceId.EnvironmentVariableId })
    }
}
