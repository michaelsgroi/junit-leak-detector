package com.salesforce.test.extensions.resourceleak

import java.time.Instant

data class DiscreteResourceInfo(
    val first: Instant,
    val last: Instant,
    val destroyed: Instant?,
    val isBaseline: Boolean
)
