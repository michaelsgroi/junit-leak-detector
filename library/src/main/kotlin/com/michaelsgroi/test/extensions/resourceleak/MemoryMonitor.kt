package com.michaelsgroi.test.extensions.resourceleak

import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Used-heap monitor (`totalMemory - freeMemory`).
 *
 * The listener calls [snapshotAfterGc] at both class-start and class-end so BEFORE_ALL and
 * AFTER_ALL values reflect retained memory rather than transient garbage. `System.gc()` is
 * a hint, but on HotSpot it generally completes within milliseconds and is sufficient to
 * make per-class growth comparable. Total time spent in GC across the suite is tracked by
 * [totalGcDuration] and logged at suite-end.
 */
class MemoryMonitor(
    private val clock: Clock = Clock.systemUTC(),
    private val gc: () -> Unit = { System.gc() },
) : NumericResourceMonitor {
    private val totalGcNanos = AtomicLong(0)
    private val gcCallCount = AtomicLong(0)

    override fun snapshot(): NumericResourceMeasurement = NumericResourceMeasurement(value = usedHeap(), timestamp = clock.instant())

    /** Force a GC and sample retained heap. Records the GC's wall-clock duration. */
    fun snapshotAfterGc(): NumericResourceMeasurement {
        val start = System.nanoTime()
        gc()
        totalGcNanos.addAndGet(System.nanoTime() - start)
        gcCallCount.incrementAndGet()
        return NumericResourceMeasurement(value = usedHeap(), timestamp = clock.instant())
    }

    val totalGcDuration: Duration get() = Duration.ofNanos(totalGcNanos.get())
    val gcInvocationCount: Long get() = gcCallCount.get()

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
