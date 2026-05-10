package com.michaelsgroi.test.extensions.resourceleak

import java.time.Clock

class MemoryMonitor(
    private val clock: Clock = Clock.systemUTC(),
) : NumericResourceMonitor {
    override fun snapshot(): NumericResourceMeasurement {
        val runtime = Runtime.getRuntime()
        return NumericResourceMeasurement(
            value = runtime.totalMemory() - runtime.freeMemory(),
            timestamp = clock.instant(),
        )
    }
}
