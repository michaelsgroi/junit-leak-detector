package com.salesforce.test.extensions.resourceleak

class EnvironmentVariableMonitor : DiscreteResourceMonitor {
    override val resourceIdClass = ResourceId.EnvironmentVariableId::class
    override fun gatherResources(): Set<ResourceId> {
        return System.getenv().keys
            .map { ResourceId.EnvironmentVariableId(it) }
            .toSet()
    }
}
