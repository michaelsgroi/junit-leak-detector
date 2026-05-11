package com.michaelsgroi.test.extensions.resourceleak

import java.time.Clock

/**
 * Used-heap monitor (`totalMemory - freeMemory`) with a "GC if it looks bad" guard. Tests can
 * generate a lot of short-lived garbage that hasn't been collected yet at sample time, which
 * inflates used-heap and can trip the growth threshold spuriously. Forcing a GC on every sample
 * is expensive and disturbs other measurements, so we only do it when the new measurement crosses
 * the configured threshold relative to the prior sample — i.e. when it would actually flag a leak.
 * If the post-GC value drops back below the threshold, that growth was transient garbage; if it
 * stays high, it's retained memory worth attributing.
 */
class MemoryMonitor(
    private val clock: Clock = Clock.systemUTC(),
    private val growthThresholdBytes: Long = DEFAULT_GROWTH_THRESHOLD_BYTES,
    private val gc: () -> Unit = { System.gc() },
) : NumericResourceMonitor {
    private var lastValue: Long = -1L

    override fun snapshot(): NumericResourceMeasurement {
        val raw = usedHeap()
        val value =
            if (lastValue >= 0 && raw - lastValue >= growthThresholdBytes) {
                gc()
                usedHeap()
            } else {
                raw
            }
        lastValue = value
        return NumericResourceMeasurement(value = value, timestamp = clock.instant())
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    companion object {
        private const val DEFAULT_GROWTH_THRESHOLD_BYTES = 1024L * 1024L * 1024L // 1 GiB
    }
}
