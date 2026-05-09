package com.salesforce.test.extensions.resourceleak

class ThreadMonitor : DiscreteResourceMonitor {
    override val resourceIdClass = ResourceId.ThreadId::class
    override fun gatherResources(): Set<ResourceId> {
        return Thread.getAllStackTraces().keys
            .filter { it.state != Thread.State.TERMINATED }
            .filter { it.name != MONITOR_THREAD_NAME }
            .map { ResourceId.ThreadId(it.name, it.id) }
            .toSet()
    }

    companion object {
        private const val MONITOR_THREAD_NAME = "ResourceMonitorThread"
    }
}
