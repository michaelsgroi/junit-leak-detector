package com.salesforce.test.extensions.resourceleak

import kotlin.reflect.KClass

interface DiscreteResourceMonitor {
    val resourceIdClass: KClass<out ResourceId>

    fun snapshot(): Set<ResourceId>
}
