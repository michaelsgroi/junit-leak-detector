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

    @Test
    fun `snapshotWithGcIfExceeds skips GC when growth is below threshold`() {
        val clock = TestClock(1000L)
        var gcCalls = 0
        val monitor = MemoryMonitor(clock = clock, gc = { gcCalls++ })
        // Set the "before" arbitrarily close to current heap so growth is small;
        // any positive baseline below current heap means delta is small.
        monitor.snapshotWithGcIfExceeds(
            beforeAllBytes = monitor.snapshot().value - 1,
            thresholdBytes = Long.MAX_VALUE,
            testClass = "com.Foo",
        )
        assertEquals(0, gcCalls)
    }

    @Test
    fun `snapshotWithGcIfExceeds invokes GC when growth crosses threshold`() {
        val clock = TestClock(1000L)
        var gcCalls = 0
        val monitor = MemoryMonitor(clock = clock, gc = { gcCalls++ })
        // beforeAll=0 means current heap (>0) appears as massive growth.
        monitor.snapshotWithGcIfExceeds(
            beforeAllBytes = 0L,
            thresholdBytes = 1L,
            testClass = "com.Foo",
        )
        assertEquals(1, gcCalls)
    }
}
