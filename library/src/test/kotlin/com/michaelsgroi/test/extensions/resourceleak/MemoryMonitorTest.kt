package com.michaelsgroi.test.extensions.resourceleak

import com.michaelsgroi.test.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryMonitorTest {
    @Test
    fun `snapshot returns positive heap usage with timestamp from clock`() {
        val clock = TestClock(1000L)
        val monitor = MemoryMonitor(clock)

        val measurement = monitor.snapshot()

        assertTrue(measurement.value > 0)
        assertEquals(clock.instant(), measurement.timestamp)
    }
}
