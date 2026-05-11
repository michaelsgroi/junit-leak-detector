package com.michaelsgroi.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Used-heap monitor (`totalMemory - freeMemory`).
 *
 * The lifecycle extension calls [snapshotWithGcIfExceeds] from `afterAll` so the recorded
 * AFTER_ALL value reflects retained memory, not transient short-lived garbage. The growth
 * threshold is per-class (AFTER_ALL minus BEFORE_ALL), matching the attribution logic in
 * `Attribution.computeMemoryLeaks`.
 */
class MemoryMonitor(
    private val clock: Clock = Clock.systemUTC(),
    private val gc: () -> Unit = { System.gc() },
) : NumericResourceMonitor {
    override fun snapshot(): NumericResourceMeasurement = NumericResourceMeasurement(value = usedHeap(), timestamp = clock.instant())

    /**
     * Sample heap; if it grew beyond [thresholdBytes] vs [beforeAllBytes], force a GC and resample.
     * Returns the post-GC measurement so the caller can record it as the AFTER_ALL snapshot.
     */
    fun snapshotWithGcIfExceeds(
        beforeAllBytes: Long,
        thresholdBytes: Long,
        testClass: String,
    ): NumericResourceMeasurement {
        val raw = usedHeap()
        if (raw - beforeAllBytes >= thresholdBytes) {
            log.info(
                "MemoryMonitor: {} grew {} MB (>= {} MB threshold); forcing GC",
                testClass,
                (raw - beforeAllBytes) / BYTES_PER_MB,
                thresholdBytes / BYTES_PER_MB,
            )
            gc()
            val post = usedHeap()
            log.info(
                "MemoryMonitor: {} post-GC growth: {} MB",
                testClass,
                (post - beforeAllBytes) / BYTES_PER_MB,
            )
            return NumericResourceMeasurement(value = post, timestamp = clock.instant())
        }
        return NumericResourceMeasurement(value = raw, timestamp = clock.instant())
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MemoryMonitor::class.java)
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
