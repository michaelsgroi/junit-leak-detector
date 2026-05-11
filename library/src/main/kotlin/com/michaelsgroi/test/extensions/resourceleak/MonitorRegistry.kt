package com.michaelsgroi.test.extensions.resourceleak

import java.time.Clock

class MonitorRegistry(
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC(),
    private val classPresent: (String) -> Boolean = ::defaultClassPresent,
    configuration: Configuration = Configuration.instance,
    private val rawReportWriter: RawReportWriter? = null,
) {
    private val discreteMonitors: List<DiscreteResourceMonitor>
    private val numericMonitors: List<NumericResourceMonitor>

    init {
        val discrete = mutableListOf<DiscreteResourceMonitor>()
        val numeric = mutableListOf<NumericResourceMonitor>()
        val property = configuration.monitoredResourceTypes
        if (property.isNotBlank()) {
            val types =
                property
                    .split(",")
                    .map { it.trim() }
                    .mapNotNull { ResourceType.fromConfigValue(it) }
                    .toSet()
            types.forEach { type ->
                when (type) {
                    ResourceType.SYSTEM_PROPS -> {
                        discrete.add(SystemPropertyMonitor(configuration.ignoredSystemProperties))
                    }

                    ResourceType.ENV_VARS -> {
                        discrete.add(EnvironmentVariableMonitor())
                    }

                    ResourceType.MEMORY -> {
                        numeric.add(
                            MemoryMonitor(
                                clock = clock,
                                growthThresholdBytes = configuration.memoryGrowthThresholdMb * 1024L * 1024L,
                            ),
                        )
                    }

                    ResourceType.THREADS -> {
                        discrete.add(ThreadMonitor())
                    }

                    ResourceType.PORTS -> {
                        discrete.add(PortMonitor())
                    }

                    ResourceType.DDBTABLES -> {
                        requireRuntimeClass(
                            className = "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
                            monitor = "ddbtables",
                            coordinate = "software.amazon.awssdk:dynamodb",
                        )
                        discrete.add(DynamoDbLocalTableMonitor())
                    }
                }
            }
        }
        discreteMonitors = discrete
        numericMonitors = numeric
    }

    fun captureBaseline() {
        val timestamp = clock.instant()
        val discrete = collectDiscrete(record = resourceState::recordBaselineDiscrete)
        val numeric = collectNumeric(record = resourceState::recordBaselineNumeric)
        rawReportWriter?.appendSnapshot(
            Snapshot(SnapshotKind.BASELINE, timestamp, null, null, discrete, numeric),
        )
    }

    fun snapshotAll(
        kind: SnapshotKind = SnapshotKind.FINAL,
        testClass: String? = null,
        testMethod: String? = null,
    ) {
        val timestamp = clock.instant()
        val discrete = collectDiscrete(record = resourceState::updateCurrentDiscrete)
        val numeric = collectNumeric(record = resourceState::updateCurrentNumeric)
        rawReportWriter?.appendSnapshot(
            Snapshot(kind, timestamp, testClass, testMethod, discrete, numeric),
        )
    }

    private fun collectDiscrete(record: (ResourceType, Set<ResourceId>) -> Unit): Map<ResourceType, Set<ResourceId>> {
        val out = mutableMapOf<ResourceType, Set<ResourceId>>()
        discreteMonitors.forEach { monitor ->
            val ids = monitor.snapshot()
            record(monitor.resourceType, ids)
            out[monitor.resourceType] = ids
        }
        return out
    }

    private fun collectNumeric(record: (NumericResourceMeasurement) -> Unit): Map<String, NumericResourceMeasurement> {
        val out = mutableMapOf<String, NumericResourceMeasurement>()
        numericMonitors.forEach { monitor ->
            val measurement = monitor.snapshot()
            record(measurement)
            out[ResourceType.MEMORY.configValue] = measurement
        }
        return out
    }

    fun hasAny(): Boolean = discreteMonitors.isNotEmpty() || numericMonitors.isNotEmpty()

    /**
     * Probes the current state of the named discrete monitors without recording the result
     * to ResourceState or emitting a snapshot record. Used by the pre-class settle wait.
     */
    fun probeDiscrete(types: Set<ResourceType>): Map<ResourceType, Set<ResourceId>> =
        discreteMonitors
            .filter { it.resourceType in types }
            .associate { it.resourceType to it.snapshot() }

    private fun requireRuntimeClass(
        className: String,
        monitor: String,
        coordinate: String,
    ) {
        if (!classPresent(className)) {
            throw IllegalStateException(
                "Monitor '$monitor' requires $coordinate on the test classpath. " +
                    "Add it as a test-scoped dependency in your project, or remove '$monitor' from " +
                    "resource.leak.detector.monitored.resource.types.",
            )
        }
    }
}

private fun defaultClassPresent(className: String): Boolean =
    try {
        Class.forName(className, false, MonitorRegistry::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    }
