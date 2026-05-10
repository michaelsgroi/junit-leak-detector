package com.michaelsgroi.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadMonitorTest {
    @Test
    fun `snapshot includes main thread`() {
        val monitor = ThreadMonitor()

        val resources = monitor.snapshot()

        assertTrue(resources.any { it is ResourceId.ThreadId && it.name == "main" })
    }

    @Test
    fun `snapshot excludes terminated threads`() {
        val monitor = ThreadMonitor()
        val thread = Thread { /* no-op */ }
        thread.name = "test-terminated-thread"
        thread.start()
        thread.join()

        val resources = monitor.snapshot()

        assertFalse(resources.any { it is ResourceId.ThreadId && it.name == "test-terminated-thread" })
    }

    @Test
    fun `snapshot returns ThreadId types`() {
        val monitor = ThreadMonitor()

        val resources = monitor.snapshot()

        assertTrue(resources.isNotEmpty())
        assertTrue(resources.all { it is ResourceId.ThreadId })
    }
}
