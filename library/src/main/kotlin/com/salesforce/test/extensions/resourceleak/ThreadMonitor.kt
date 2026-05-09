package com.salesforce.test.extensions.resourceleak

class ThreadMonitor : DiscreteResourceMonitor {
    override val resourceType = ResourceType.THREADS

    override fun snapshot(): Set<ResourceId> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.state != Thread.State.TERMINATED }
            .map { ResourceId.ThreadId(it.name, it.id) }
            .toSet()
}
