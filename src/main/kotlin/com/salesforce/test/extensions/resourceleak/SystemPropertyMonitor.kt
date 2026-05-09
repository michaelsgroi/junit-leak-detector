package com.salesforce.test.extensions.resourceleak

class SystemPropertyMonitor : DiscreteResourceMonitor {
    override val resourceIdClass = ResourceId.PropertyId::class
    override fun gatherResources(): Set<ResourceId> {
        return System.getProperties().stringPropertyNames()
            .map { ResourceId.PropertyId(it) }
            .toSet()
    }
}
