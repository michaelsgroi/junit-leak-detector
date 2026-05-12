package com.michaelsgroi.test.extensions.resourceleak

class SystemPropertyMonitor(
    ignored: Set<String> = emptySet(),
) : DiscreteResourceMonitor {
    override val resourceType = ResourceType.SYSTEM_PROPS

    private val effectiveIgnored: Set<String> = ignored + ALWAYS_IGNORED

    override fun snapshot(): Set<ResourceId> =
        System
            .getProperties()
            .stringPropertyNames()
            .asSequence()
            .filter { it !in effectiveIgnored }
            .map { ResourceId.PropertyId(it) }
            .toSet()

    companion object {
        /**
         * Properties the detector itself causes to appear and would otherwise self-report as leaks.
         * `jdk.jfr.repository` is set by the JVM the first time JFR is used — the detector's own
         * `ThreadCreationRecorder` triggers that on most runs.
         */
        private val ALWAYS_IGNORED = setOf("jdk.jfr.repository")
    }
}
