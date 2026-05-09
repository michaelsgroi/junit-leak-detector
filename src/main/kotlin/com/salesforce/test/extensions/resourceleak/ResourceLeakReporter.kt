package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.system.exitProcess

class ResourceLeakReporter(
    private val resourceState: ResourceState = ResourceState.instance,
    private val configuration: Configuration = Configuration.instance,
    private val buildFailureAction: () -> Unit = { exitProcess(1) }
) {
    fun report() {
        val testClassLifecycles = resourceState.getAllTestClassLifecycles()
        val testClassCount = testClassLifecycles.size

        LOG.info("Resource Leak Detector Report")
        LOG.info("==============================")
        LOG.info("Test Classes Executed: $testClassCount")

        if (testClassCount > 0) {
            LOG.info("")
            LOG.info("Test Class Execution Times:")
            testClassLifecycles.forEach { (testClassName, lifecycle) ->
                val duration = Duration.between(lifecycle.start, lifecycle.end)
                val durationMillis = duration.toMillis()
                LOG.info("  - $testClassName: ${durationMillis}ms")
            }
        }

        reportSystemPropertyLeaks(testClassLifecycles)
        reportEnvironmentVariableLeaks(testClassLifecycles)
        reportThreadLeaks(testClassLifecycles)
        reportPortLeaks(testClassLifecycles)
        reportDynamoDbTableLeaks(testClassLifecycles)
        reportMemoryLeaks(testClassLifecycles)
        checkBuildFailure()
    }

    private fun reportSystemPropertyLeaks(testClassLifecycles: Map<TestClassName, TestClassLifecycle>) {
        if (!isResourceTypeMonitored(ResourceType.SYSTEM_PROPS)) {
            return
        }

        val allResources = resourceState.getAllDiscreteResources()
        val leakedProperties = allResources
            .filter { (resourceId, info) ->
                resourceId is ResourceId.PropertyId &&
                    !info.isBaseline &&
                    info.destroyed == null
            }
            .map { (resourceId, info) -> resourceId as ResourceId.PropertyId to info }
            .toList()

        if (leakedProperties.isEmpty()) {
            return
        }

        LOG.info("")
        LOG.info("System Property Leaks:")
        leakedProperties.forEach { (propertyId, info) ->
            val candidateTestClasses = findCandidateTestClasses(
                info.first,
                testClassLifecycles
            )
            LOG.info("  - Property: ${propertyId.name}")
            LOG.info("    First Detected: ${info.first}")
            if (candidateTestClasses.isNotEmpty()) {
                LOG.info("    Candidate Test Classes:")
                candidateTestClasses.forEach { (testClassName, lifecycle) ->
                    LOG.info("      - $testClassName")
                    LOG.info("        Started: ${lifecycle.start}")
                    LOG.info("        Ended: ${lifecycle.end}")
                }
            }
        }
    }

    private fun reportEnvironmentVariableLeaks(testClassLifecycles: Map<TestClassName, TestClassLifecycle>) {
        if (!isResourceTypeMonitored(ResourceType.ENV_VARS)) {
            return
        }

        val allResources = resourceState.getAllDiscreteResources()
        val leakedEnvVars = allResources
            .filter { (resourceId, info) ->
                resourceId is ResourceId.EnvironmentVariableId &&
                    !info.isBaseline &&
                    info.destroyed == null
            }
            .map { (resourceId, info) -> resourceId as ResourceId.EnvironmentVariableId to info }
            .toList()

        if (leakedEnvVars.isEmpty()) {
            return
        }

        LOG.info("")
        LOG.info("Environment Variable Leaks:")
        leakedEnvVars.forEach { (envVarId, info) ->
            val candidateTestClasses = findCandidateTestClasses(
                info.first,
                testClassLifecycles
            )
            LOG.info("  - Variable: ${envVarId.name}")
            LOG.info("    First Detected: ${info.first}")
            if (candidateTestClasses.isNotEmpty()) {
                LOG.info("    Candidate Test Classes:")
                candidateTestClasses.forEach { (testClassName, lifecycle) ->
                    LOG.info("      - $testClassName")
                    LOG.info("        Started: ${lifecycle.start}")
                    LOG.info("        Ended: ${lifecycle.end}")
                }
            }
        }
    }

    private fun reportThreadLeaks(testClassLifecycles: Map<TestClassName, TestClassLifecycle>) {
        if (!isResourceTypeMonitored(ResourceType.THREADS)) {
            return
        }

        val allResources = resourceState.getAllDiscreteResources()
        var leakedThreads = allResources
            .filter { (resourceId, info) ->
                resourceId is ResourceId.ThreadId &&
                    !info.isBaseline &&
                    info.destroyed == null
            }
            .map { (resourceId, info) -> resourceId as ResourceId.ThreadId to info }
            .toList()

        if (leakedThreads.isEmpty()) {
            return
        }

        // Grace period: wait and re-check to filter out threads that terminate during the grace period
        val gracePeriodSeconds = configuration.threadGracePeriodSeconds
        try {
            Thread.sleep(gracePeriodSeconds * 1000)
        } catch (_: InterruptedException) {
            // interrupted, proceed with current state
        }

        val currentThreads = ThreadMonitor().snapshot()
        leakedThreads = leakedThreads.filter { (threadId, _) -> threadId in currentThreads }

        if (leakedThreads.isEmpty()) {
            return
        }

        LOG.info("")
        LOG.info("Thread Leaks:")
        leakedThreads.forEach { (threadId, info) ->
            val candidateTestClasses = findCandidateTestClasses(
                info.first,
                testClassLifecycles
            )
            LOG.info("  - Thread: ${threadId.name} (ID: ${threadId.id})")
            LOG.info("    First Detected: ${info.first}")
            if (candidateTestClasses.isNotEmpty()) {
                LOG.info("    Candidate Test Classes:")
                candidateTestClasses.forEach { (testClassName, lifecycle) ->
                    LOG.info("      - $testClassName")
                    LOG.info("        Started: ${lifecycle.start}")
                    LOG.info("        Ended: ${lifecycle.end}")
                }
            }
        }
    }

    private fun reportPortLeaks(testClassLifecycles: Map<TestClassName, TestClassLifecycle>) {
        if (!isResourceTypeMonitored(ResourceType.PORTS)) {
            return
        }

        val allResources = resourceState.getAllDiscreteResources()
        val leakedPorts = allResources
            .filter { (resourceId, info) ->
                resourceId is ResourceId.PortId &&
                    !info.isBaseline &&
                    info.destroyed == null
            }
            .map { (resourceId, info) -> resourceId as ResourceId.PortId to info }
            .toList()

        if (leakedPorts.isEmpty()) {
            return
        }

        LOG.info("")
        LOG.info("Port Leaks:")
        leakedPorts.forEach { (portId, info) ->
            val candidateTestClasses = findCandidateTestClasses(
                info.first,
                testClassLifecycles
            )
            LOG.info("  - Port: ${portId.port}")
            LOG.info("    First Detected: ${info.first}")
            if (candidateTestClasses.isNotEmpty()) {
                LOG.info("    Candidate Test Classes:")
                candidateTestClasses.forEach { (testClassName, lifecycle) ->
                    LOG.info("      - $testClassName")
                    LOG.info("        Started: ${lifecycle.start}")
                    LOG.info("        Ended: ${lifecycle.end}")
                }
            }
        }
    }

    private fun reportDynamoDbTableLeaks(testClassLifecycles: Map<TestClassName, TestClassLifecycle>) {
        if (!isResourceTypeMonitored(ResourceType.DDBTABLES)) {
            return
        }

        val allResources = resourceState.getAllDiscreteResources()
        val leakedTables = allResources
            .filter { (resourceId, info) ->
                resourceId is ResourceId.DynamoDbTableId &&
                    !info.isBaseline &&
                    info.destroyed == null
            }
            .map { (resourceId, info) -> resourceId as ResourceId.DynamoDbTableId to info }
            .toList()

        if (leakedTables.isEmpty()) {
            return
        }

        LOG.info("")
        LOG.info("DynamoDB Table Leaks:")
        leakedTables.forEach { (tableId, info) ->
            val candidateTestClasses = findCandidateTestClasses(
                info.first,
                testClassLifecycles
            )
            LOG.info("  - Table: ${tableId.name}")
            LOG.info("    First Detected: ${info.first}")
            if (candidateTestClasses.isNotEmpty()) {
                LOG.info("    Candidate Test Classes:")
                candidateTestClasses.forEach { (testClassName, lifecycle) ->
                    LOG.info("      - $testClassName")
                    LOG.info("        Started: ${lifecycle.start}")
                    LOG.info("        Ended: ${lifecycle.end}")
                }
            }
        }
    }

    private fun reportMemoryLeaks(testClassLifecycles: Map<TestClassName, TestClassLifecycle>) {
        if (!isResourceTypeMonitored(ResourceType.MEMORY)) {
            return
        }

        val measurements = resourceState.getAllNumericMeasurements()
        if (measurements.size < 2) {
            return
        }

        val baseline = measurements.first()
        val final_ = measurements.last()
        val growthBytes = final_.value - baseline.value
        val thresholdBytes = getMemoryGrowthThresholdBytes()

        if (growthBytes <= thresholdBytes) {
            return
        }

        val baselineMb = baseline.value / BYTES_PER_MB
        val finalMb = final_.value / BYTES_PER_MB
        val increaseMb = growthBytes / BYTES_PER_MB

        val firstDetected = measurements.firstOrNull { it.value - baseline.value > thresholdBytes }
        val firstDetectedTimestamp = firstDetected?.timestamp ?: final_.timestamp

        val candidateTestClasses = findCandidateTestClasses(firstDetectedTimestamp, testClassLifecycles)

        LOG.info("")
        LOG.info("Memory Leaks:")
        LOG.info("  - Baseline: $baselineMb MB")
        LOG.info("  - Final: $finalMb MB")
        LOG.info("  - Increase: $increaseMb MB")
        LOG.info("  - First Detected: $firstDetectedTimestamp")
        if (candidateTestClasses.isNotEmpty()) {
            LOG.info("  - Candidate Test Classes:")
            candidateTestClasses.forEach { (testClassName, lifecycle) ->
                LOG.info("    - $testClassName")
                LOG.info("      Started: ${lifecycle.start}")
                LOG.info("      Ended: ${lifecycle.end}")
            }
        }
    }

    private fun isResourceTypeMonitored(resourceType: ResourceType): Boolean {
        val property = configuration.monitoredResourceTypes
        if (property.isBlank()) {
            return false
        }
        return property.split(",")
            .map { it.trim() }
            .any { ResourceType.fromConfigValue(it) == resourceType }
    }

    private fun findCandidateTestClasses(
        resourceFirstDetected: java.time.Instant,
        testClassLifecycles: Map<TestClassName, TestClassLifecycle>
    ): Map<TestClassName, TestClassLifecycle> {
        return testClassLifecycles.filter { (_, lifecycle) ->
            !resourceFirstDetected.isBefore(lifecycle.start) &&
                !resourceFirstDetected.isAfter(lifecycle.end)
        }
    }

    private fun checkBuildFailure() {
        val property = configuration.buildFailureResourceTypes
        if (property.isBlank()) {
            return
        }
        val buildFailureResourceTypes = property.split(",")
            .map { it.trim() }
            .mapNotNull { ResourceType.fromConfigValue(it) }

        val failingResourceTypes = buildFailureResourceTypes.filter { hasLeaksForResourceType(it) }

        if (failingResourceTypes.isNotEmpty()) {
            LOG.error("Build failure triggered - leaks detected for resource types: ${failingResourceTypes.joinToString(", ") { it.configValue }}")
            buildFailureAction()
        }
    }

    private fun hasLeaksForResourceType(resourceType: ResourceType): Boolean {
        return when (resourceType) {
            ResourceType.SYSTEM_PROPS -> hasDiscreteLeaksForResourceType(resourceType)
            ResourceType.ENV_VARS -> hasDiscreteLeaksForResourceType(resourceType)
            ResourceType.THREADS -> hasDiscreteLeaksForResourceType(resourceType)
            ResourceType.PORTS -> hasDiscreteLeaksForResourceType(resourceType)
            ResourceType.DDBTABLES -> hasDiscreteLeaksForResourceType(resourceType)
            ResourceType.MEMORY -> hasMemoryLeak()
        }
    }

    private fun hasDiscreteLeaksForResourceType(resourceType: ResourceType): Boolean {
        val allResources = resourceState.getAllDiscreteResources()
        return allResources.any { (resourceId, info) ->
            resourceIdMatchesType(resourceId, resourceType) &&
                !info.isBaseline &&
                info.destroyed == null
        }
    }

    private fun hasMemoryLeak(): Boolean {
        val measurements = resourceState.getAllNumericMeasurements()
        if (measurements.size < 2) {
            return false
        }
        val baseline = measurements.first()
        val final_ = measurements.last()
        val growthBytes = final_.value - baseline.value
        return growthBytes > getMemoryGrowthThresholdBytes()
    }

    private fun getMemoryGrowthThresholdBytes(): Long {
        return configuration.memoryGrowthThresholdMb * BYTES_PER_MB
    }

    private fun resourceIdMatchesType(resourceId: ResourceId, resourceType: ResourceType): Boolean {
        return when (resourceType) {
            ResourceType.SYSTEM_PROPS -> resourceId is ResourceId.PropertyId
            ResourceType.ENV_VARS -> resourceId is ResourceId.EnvironmentVariableId
            ResourceType.THREADS -> resourceId is ResourceId.ThreadId
            ResourceType.PORTS -> resourceId is ResourceId.PortId
            ResourceType.DDBTABLES -> resourceId is ResourceId.DynamoDbTableId
            ResourceType.MEMORY -> false
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ResourceLeakReporter::class.java)
        private const val BYTES_PER_MB = 1_048_576L
    }
}
