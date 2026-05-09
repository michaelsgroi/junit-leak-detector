package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import kotlin.system.exitProcess

class ResourceLeakReporter(
    private val resourceState: ResourceState = ResourceState.instance,
    private val configuration: Configuration = Configuration.instance,
    private val buildFailureAction: () -> Unit = { exitProcess(1) },
    private val textOutputFile: File? = null,
) {
    private val textLines = mutableListOf<String>()

    fun report() {
        val testClassLifecycles = resourceState.getAllTestClassLifecycles()
        val testClassCount = testClassLifecycles.size

        emit("Resource Leak Detector Report")
        emit("==============================")
        emit("Test Classes Executed: $testClassCount")

        if (testClassCount > 0) {
            emit("")
            emit("Test Class Execution Times:")
            testClassLifecycles.forEach { (testClassName, lifecycle) ->
                val durationMillis = Duration.between(lifecycle.start, lifecycle.end).toMillis()
                emit("  - $testClassName: ${durationMillis}ms")
            }
        }

        reportDiscreteLeaks(ResourceType.SYSTEM_PROPS, "System Property Leaks", "Property") { (it as ResourceId.PropertyId).name }
        reportDiscreteLeaks(
            ResourceType.ENV_VARS,
            "Environment Variable Leaks",
            "Variable",
        ) { (it as ResourceId.EnvironmentVariableId).name }
        reportThreadLeaks()
        reportDiscreteLeaks(ResourceType.PORTS, "Port Leaks", "Port") { (it as ResourceId.PortId).port.toString() }
        reportDiscreteLeaks(ResourceType.DDBTABLES, "DynamoDB Table Leaks", "Table") { (it as ResourceId.DynamoDbTableId).name }
        reportMemoryLeaks()
        flushTextFile()
        checkBuildFailure()
    }

    private fun reportDiscreteLeaks(
        resourceType: ResourceType,
        header: String,
        label: String,
        renderValue: (ResourceId) -> String,
    ) {
        if (!isResourceTypeMonitored(resourceType)) return
        val leaks = leakedDiscrete(resourceType)
        if (leaks.isEmpty()) return

        emit("")
        emit("$header:")
        leaks.forEach { resourceId ->
            emit("  - $label: ${renderValue(resourceId)}")
        }
    }

    private fun reportThreadLeaks() {
        if (!isResourceTypeMonitored(ResourceType.THREADS)) return
        val leaks = leakedDiscrete(ResourceType.THREADS)
        if (leaks.isEmpty()) return

        emit("")
        emit("Thread Leaks:")
        leaks.forEach { resourceId ->
            val threadId = resourceId as ResourceId.ThreadId
            emit("  - Thread: ${threadId.name} (ID: ${threadId.id})")
        }
    }

    private fun reportMemoryLeaks() {
        if (!isResourceTypeMonitored(ResourceType.MEMORY)) return

        val baseline = resourceState.getBaselineNumeric() ?: return
        val final = resourceState.getCurrentNumeric() ?: return
        val growthBytes = final.value - baseline.value
        val thresholdBytes = configuration.memoryGrowthThresholdMb * BYTES_PER_MB
        if (growthBytes <= thresholdBytes) return

        val baselineMb = baseline.value / BYTES_PER_MB
        val finalMb = final.value / BYTES_PER_MB
        val increaseMb = growthBytes / BYTES_PER_MB

        emit("")
        emit("Memory Leaks:")
        emit("  - Baseline: $baselineMb MB")
        emit("  - Final: $finalMb MB")
        emit("  - Increase: $increaseMb MB")
    }

    private fun emit(line: String) {
        LOG.info(line)
        textLines += line
    }

    private fun flushTextFile() {
        val file = textOutputFile ?: return
        file.parentFile?.mkdirs()
        file.writeText(textLines.joinToString(System.lineSeparator()) + System.lineSeparator())
    }

    private fun leakedDiscrete(resourceType: ResourceType): Set<ResourceId> {
        val resourceIdClass = resourceIdClassFor(resourceType) ?: return emptySet()
        val baseline = resourceState.getBaselineDiscrete(resourceIdClass)
        val current = resourceState.getCurrentDiscrete(resourceIdClass)
        return current - baseline
    }

    private fun isResourceTypeMonitored(resourceType: ResourceType): Boolean {
        val property = configuration.monitoredResourceTypes
        if (property.isBlank()) return false
        return property
            .split(",")
            .map { it.trim() }
            .any { ResourceType.fromConfigValue(it) == resourceType }
    }

    private fun checkBuildFailure() {
        val property = configuration.buildFailureResourceTypes
        if (property.isBlank()) return
        val buildFailureResourceTypes =
            property
                .split(",")
                .map { it.trim() }
                .mapNotNull { ResourceType.fromConfigValue(it) }

        val failingResourceTypes = buildFailureResourceTypes.filter { hasLeaksForResourceType(it) }

        if (failingResourceTypes.isNotEmpty()) {
            LOG.error(
                "Build failure triggered - leaks detected for resource types: ${failingResourceTypes.joinToString(
                    ", ",
                ) { it.configValue }}",
            )
            buildFailureAction()
        }
    }

    private fun hasLeaksForResourceType(resourceType: ResourceType): Boolean =
        when (resourceType) {
            ResourceType.MEMORY -> hasMemoryLeak()
            else -> leakedDiscrete(resourceType).isNotEmpty()
        }

    private fun hasMemoryLeak(): Boolean {
        val baseline = resourceState.getBaselineNumeric() ?: return false
        val final = resourceState.getCurrentNumeric() ?: return false
        return final.value - baseline.value > configuration.memoryGrowthThresholdMb * BYTES_PER_MB
    }

    private fun resourceIdClassFor(resourceType: ResourceType) =
        when (resourceType) {
            ResourceType.SYSTEM_PROPS -> ResourceId.PropertyId::class
            ResourceType.ENV_VARS -> ResourceId.EnvironmentVariableId::class
            ResourceType.THREADS -> ResourceId.ThreadId::class
            ResourceType.PORTS -> ResourceId.PortId::class
            ResourceType.DDBTABLES -> ResourceId.DynamoDbTableId::class
            ResourceType.MEMORY -> null
        }

    companion object {
        private val LOG = LoggerFactory.getLogger(ResourceLeakReporter::class.java)
        private const val BYTES_PER_MB = 1_048_576L
    }
}
