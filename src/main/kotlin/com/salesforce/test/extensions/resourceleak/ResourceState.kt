package com.salesforce.test.extensions.resourceleak

import java.time.Instant
import kotlin.reflect.KClass

typealias TestClassName = String

data class TestClassLifecycle(
    val start: Instant,
    val end: Instant
)

class ResourceState {
    private val testClassLifecycles = mutableMapOf<TestClassName, TestClassLifecycle>()
    private var baselinePeriodEndTimestamp: Instant? = null
    private val discreteResources = mutableMapOf<ResourceId, DiscreteResourceInfo>()
    private val numericMeasurements = mutableListOf<NumericResourceMeasurement>()

    fun recordTestClassStart(testClassName: TestClassName, startTimestamp: Instant) {
        val isFirstTestClass = testClassLifecycles.isEmpty()
        if (isFirstTestClass) {
            baselinePeriodEndTimestamp = startTimestamp
        }
        testClassLifecycles[testClassName] = TestClassLifecycle(
            start = startTimestamp,
            end = startTimestamp
        )
    }

    fun recordTestClassEnd(testClassName: TestClassName, endTimestamp: Instant) {
        val existing = testClassLifecycles[testClassName]
        if (existing != null) {
            testClassLifecycles[testClassName] = existing.copy(end = endTimestamp)
        }
    }

    fun getAllTestClassLifecycles(): Map<TestClassName, TestClassLifecycle> {
        return testClassLifecycles.toMap()
    }

    fun recordBaselineResources(resources: Set<ResourceId>, timestamp: Instant) {
        resources.forEach { resourceId ->
            discreteResources[resourceId] = DiscreteResourceInfo(
                first = timestamp,
                last = timestamp,
                destroyed = null,
                isBaseline = true
            )
        }
    }

    fun updateDiscreteResources(
        currentResources: Set<ResourceId>,
        timestamp: Instant,
        resourceIdType: KClass<out ResourceId>? = null
    ): Set<ResourceId> {
        val isBaseline = false
        val existingResourceIds = if (resourceIdType != null) {
            discreteResources.keys.filter { resourceIdType.isInstance(it) }.toSet()
        } else {
            discreteResources.keys.toSet()
        }
        val newlyDetected = currentResources - existingResourceIds
        val noLongerDetected = existingResourceIds - currentResources
        val stillDetected = currentResources intersect existingResourceIds
        val recreatedResources = mutableSetOf<ResourceId>()

        newlyDetected.forEach { resourceId ->
            discreteResources[resourceId] = DiscreteResourceInfo(
                first = timestamp,
                last = timestamp,
                destroyed = null,
                isBaseline = isBaseline
            )
        }

        noLongerDetected.forEach { resourceId ->
            val existing = discreteResources[resourceId]
            if (existing != null && existing.destroyed == null) {
                discreteResources[resourceId] = existing.copy(destroyed = timestamp)
            }
        }

        stillDetected.forEach { resourceId ->
            val existing = discreteResources[resourceId]
            if (existing != null) {
                if (existing.destroyed != null) {
                    recreatedResources.add(resourceId)
                    discreteResources[resourceId] = DiscreteResourceInfo(
                        first = existing.first,
                        last = timestamp,
                        destroyed = null,
                        isBaseline = existing.isBaseline
                    )
                } else {
                    discreteResources[resourceId] = existing.copy(last = timestamp)
                }
            }
        }

        return recreatedResources
    }

    fun getAllDiscreteResources(): Map<ResourceId, DiscreteResourceInfo> {
        return discreteResources.toMap()
    }

    fun recordNumericMeasurement(measurement: NumericResourceMeasurement) {
        numericMeasurements.add(measurement)
    }

    fun getAllNumericMeasurements(): List<NumericResourceMeasurement> {
        return numericMeasurements.toList()
    }

    fun getBaselinePeriodEndTimestamp(): Instant? {
        return baselinePeriodEndTimestamp
    }

    companion object {
        @JvmStatic
        val instance: ResourceState = ResourceState()
    }
}
