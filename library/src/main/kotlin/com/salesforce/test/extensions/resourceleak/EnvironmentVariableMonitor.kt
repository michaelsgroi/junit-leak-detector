package com.salesforce.test.extensions.resourceleak

class EnvironmentVariableMonitor : DiscreteResourceMonitor {
    override val resourceType = ResourceType.ENV_VARS

    override fun snapshot(): Set<ResourceId> =
        System
            .getenv()
            .keys
            .map { ResourceId.EnvironmentVariableId(it) }
            .toSet()
}
