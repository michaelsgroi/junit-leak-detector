package com.michaelsgroi.test.extensions.resourceleak

class SystemPropertyMonitor(
    private val ignored: Set<String> = emptySet(),
) : DiscreteResourceMonitor {
    override val resourceType = ResourceType.SYSTEM_PROPS

    override fun snapshot(): Set<ResourceId> =
        System
            .getProperties()
            .stringPropertyNames()
            .asSequence()
            .filter { it !in ignored }
            .map { ResourceId.PropertyId(it) }
            .toSet()
}
