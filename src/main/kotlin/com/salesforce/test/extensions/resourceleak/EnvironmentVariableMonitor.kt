package com.salesforce.test.extensions.resourceleak

class EnvironmentVariableMonitor : DiscreteResourceMonitor {
    override val resourceIdClass = ResourceId.EnvironmentVariableId::class
    override fun snapshot(): Set<ResourceId> {
        return System.getenv().keys
            .map { ResourceId.EnvironmentVariableId(it) }
            .toSet()
    }
}
