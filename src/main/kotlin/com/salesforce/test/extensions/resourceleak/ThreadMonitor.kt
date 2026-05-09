package com.salesforce.test.extensions.resourceleak

class ThreadMonitor : DiscreteResourceMonitor {
    override val resourceIdClass = ResourceId.ThreadId::class
    override fun snapshot(): Set<ResourceId> {
        return Thread.getAllStackTraces().keys
            .filter { it.state != Thread.State.TERMINATED }
            .map { ResourceId.ThreadId(it.name, it.id) }
            .toSet()
    }
}
