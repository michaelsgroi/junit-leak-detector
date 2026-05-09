package com.salesforce.test.extensions.resourceleak

import java.time.Instant

data class NumericResourceMeasurement(
    val value: Long,
    val timestamp: Instant
)

interface NumericResourceMonitor {
    fun snapshot(): NumericResourceMeasurement
}
