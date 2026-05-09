package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import java.time.Clock

class ResourceMonitorThread(
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC(),
    private val classPresent: (String) -> Boolean = ::defaultClassPresent,
    private val configuration: Configuration = Configuration.instance
) : Thread("ResourceMonitorThread") {
    private var shouldStop = false
    private val monitors: MutableList<DiscreteResourceMonitor>
    private val numericMonitors: MutableList<NumericResourceMonitor>
    private val pollingIntervalMs = configuration.pollingIntervalMs
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        monitors = mutableListOf()
        numericMonitors = mutableListOf()
        initializeMonitors()
        captureBaseline()
    }

    override fun run() {
        try {
            startPolling()
        } finally {
            detectResources() // final detection
            measureNumericResources()
            triggerReporting()
        }
    }

    fun stopMonitoring() {
        shouldStop = true
        interrupt()
        join()
    }

    private fun initializeMonitors() {
        val property = configuration.monitoredResourceTypes
        if (property.isBlank()) {
            return
        }
        val monitoredResourceTypes =
            property.split(",").map { it.trim() }.mapNotNull { ResourceType.fromConfigValue(it) }.toSet()
        monitoredResourceTypes.forEach { resourceType ->
            when (resourceType) {
                ResourceType.SYSTEM_PROPS -> monitors.add(SystemPropertyMonitor())
                ResourceType.ENV_VARS -> monitors.add(EnvironmentVariableMonitor())
                ResourceType.MEMORY -> numericMonitors.add(MemoryMonitor(clock))
                ResourceType.THREADS -> monitors.add(ThreadMonitor())
                ResourceType.PORTS -> monitors.add(PortMonitor())
                ResourceType.DDBTABLES -> {
                    requireRuntimeClass(
                        className = "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
                        monitor = "ddbtables",
                        coordinate = "software.amazon.awssdk:dynamodb"
                    )
                    monitors.add(DynamoDbLocalTableMonitor())
                }
            }
        }
    }

    private fun captureBaseline() {
        monitors.forEach { monitor ->
            val resources = timed(monitor) { monitor.gatherResources() }
            resourceState.recordBaselineResources(resources, clock.instant())
        }
        numericMonitors.forEach { monitor ->
            val measurement = timed(monitor) { monitor.measureResource() }
            resourceState.recordNumericMeasurement(measurement)
        }
    }

    private fun startPolling() {
        while (!shouldStop) {
            detectResources()
            measureNumericResources()
            try {
                sleep(pollingIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun requireRuntimeClass(className: String, monitor: String, coordinate: String) {
        if (!classPresent(className)) {
            throw IllegalStateException(
                "Monitor '$monitor' requires $coordinate on the test classpath. " +
                    "Add it as a test-scoped dependency in your project, or remove '$monitor' from " +
                    "resource.leak.detector.monitored.resource.types."
            )
        }
    }

    private fun detectResources() {
        monitors.forEach { monitor ->
            val resources = timed(monitor) { monitor.gatherResources() }
            val recreatedResources =
                resourceState.updateDiscreteResources(resources, clock.instant(), monitor.resourceIdClass)
            recreatedResources.forEach { resourceId ->
                log.warn("Resource $resourceId was recreated after being destroyed")
            }
        }
    }

    private fun measureNumericResources() {
        numericMonitors.forEach { monitor ->
            val measurement = timed(monitor) { monitor.measureResource() }
            resourceState.recordNumericMeasurement(measurement)
        }
    }

    private inline fun <T> timed(monitor: Any, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val totalMonitors = monitors.size + numericMonitors.size
        val budgetMs = if (totalMonitors > 0) pollingIntervalMs / totalMonitors else pollingIntervalMs
        if (elapsedMs > budgetMs) {
            log.warn(
                "Monitor {} took {}ms which exceeds per-monitor budget {}ms (polling interval {}ms / {} monitors)",
                monitor.javaClass.simpleName, elapsedMs, budgetMs, pollingIntervalMs, totalMonitors
            )
        }
        return result
    }

    private fun triggerReporting() = ResourceLeakReporter(resourceState, configuration).report()
}

private fun defaultClassPresent(className: String): Boolean = try {
    Class.forName(className, false, ResourceMonitorThread::class.java.classLoader)
    true
} catch (_: ClassNotFoundException) {
    false
}

