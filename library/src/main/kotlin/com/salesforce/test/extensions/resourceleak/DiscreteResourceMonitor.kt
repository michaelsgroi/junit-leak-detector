package com.salesforce.test.extensions.resourceleak

interface DiscreteResourceMonitor {
    val resourceType: ResourceType

    fun snapshot(): Set<ResourceId>
}
