package com.salesforce.test.extensions.resourceleak

import java.time.Clock

class MonitorRegistry(
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC(),
    private val classPresent: (String) -> Boolean = ::defaultClassPresent,
    configuration: Configuration = Configuration.instance
) {
    private val discreteMonitors: List<DiscreteResourceMonitor>
    private val numericMonitors: List<NumericResourceMonitor>

    init {
        val discrete = mutableListOf<DiscreteResourceMonitor>()
        val numeric = mutableListOf<NumericResourceMonitor>()
        val property = configuration.monitoredResourceTypes
        if (property.isNotBlank()) {
            val types = property.split(",")
                .map { it.trim() }
                .mapNotNull { ResourceType.fromConfigValue(it) }
                .toSet()
            types.forEach { type ->
                when (type) {
                    ResourceType.SYSTEM_PROPS -> discrete.add(SystemPropertyMonitor())
                    ResourceType.ENV_VARS -> discrete.add(EnvironmentVariableMonitor())
                    ResourceType.MEMORY -> numeric.add(MemoryMonitor(clock))
                    ResourceType.THREADS -> discrete.add(ThreadMonitor())
                    ResourceType.PORTS -> discrete.add(PortMonitor())
                    ResourceType.DDBTABLES -> {
                        requireRuntimeClass(
                            className = "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
                            monitor = "ddbtables",
                            coordinate = "software.amazon.awssdk:dynamodb"
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
        discreteMonitors.forEach { monitor ->
            resourceState.recordBaselineDiscrete(monitor.resourceIdClass, monitor.snapshot())
        }
        numericMonitors.forEach { monitor ->
            resourceState.recordBaselineNumeric(monitor.snapshot())
        }
    }

    fun snapshotAll() {
        discreteMonitors.forEach { monitor ->
            resourceState.updateCurrentDiscrete(monitor.resourceIdClass, monitor.snapshot())
        }
        numericMonitors.forEach { monitor ->
            resourceState.updateCurrentNumeric(monitor.snapshot())
        }
    }

    fun hasAny(): Boolean = discreteMonitors.isNotEmpty() || numericMonitors.isNotEmpty()

    private fun requireRuntimeClass(className: String, monitor: String, coordinate: String) {
        if (!classPresent(className)) {
            throw IllegalStateException(
                "Monitor '$monitor' requires $coordinate on the test classpath. " +
                    "Add it as a test-scoped dependency in your project, or remove '$monitor' from " +
                    "resource.leak.detector.monitored.resource.types."
            )
        }
    }
}

private fun defaultClassPresent(className: String): Boolean = try {
    Class.forName(className, false, MonitorRegistry::class.java.classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}
