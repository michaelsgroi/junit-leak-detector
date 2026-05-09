package com.salesforce.test.extensions.resourceleak

import java.time.Instant

typealias TestClassName = String

data class TestClassLifecycle(
    val start: Instant,
    val end: Instant,
)

data class TestMethodKey(
    val testClassName: TestClassName,
    val testMethodName: String,
)

class ResourceState {
    private val testClassLifecycles = mutableMapOf<TestClassName, TestClassLifecycle>()
    private val testMethodLifecycles = mutableMapOf<TestMethodKey, TestClassLifecycle>()
    private val baselineDiscrete = mutableMapOf<ResourceType, Set<ResourceId>>()
    private val currentDiscrete = mutableMapOf<ResourceType, Set<ResourceId>>()
    private var baselineNumeric: NumericResourceMeasurement? = null
    private var currentNumeric: NumericResourceMeasurement? = null

    fun recordTestClassStart(
        testClassName: TestClassName,
        startTimestamp: Instant,
    ) {
        testClassLifecycles[testClassName] = TestClassLifecycle(start = startTimestamp, end = startTimestamp)
    }

    fun recordTestClassEnd(
        testClassName: TestClassName,
        endTimestamp: Instant,
    ) {
        val existing = testClassLifecycles[testClassName] ?: return
        testClassLifecycles[testClassName] = existing.copy(end = endTimestamp)
    }

    fun recordTestMethodStart(
        key: TestMethodKey,
        startTimestamp: Instant,
    ) {
        testMethodLifecycles[key] = TestClassLifecycle(start = startTimestamp, end = startTimestamp)
    }

    fun recordTestMethodEnd(
        key: TestMethodKey,
        endTimestamp: Instant,
    ) {
        val existing = testMethodLifecycles[key] ?: return
        testMethodLifecycles[key] = existing.copy(end = endTimestamp)
    }

    fun getAllTestClassLifecycles(): Map<TestClassName, TestClassLifecycle> = testClassLifecycles.toMap()

    fun getAllTestMethodLifecycles(): Map<TestMethodKey, TestClassLifecycle> = testMethodLifecycles.toMap()

    fun recordBaselineDiscrete(
        resourceType: ResourceType,
        resources: Set<ResourceId>,
    ) {
        baselineDiscrete[resourceType] = resources.toSet()
        currentDiscrete[resourceType] = resources.toSet()
    }

    fun updateCurrentDiscrete(
        resourceType: ResourceType,
        resources: Set<ResourceId>,
    ) {
        currentDiscrete[resourceType] = resources.toSet()
    }

    fun getBaselineDiscrete(resourceType: ResourceType): Set<ResourceId> = baselineDiscrete[resourceType].orEmpty()

    fun getCurrentDiscrete(resourceType: ResourceType): Set<ResourceId> = currentDiscrete[resourceType].orEmpty()

    fun recordBaselineNumeric(measurement: NumericResourceMeasurement) {
        baselineNumeric = measurement
        currentNumeric = measurement
    }

    fun updateCurrentNumeric(measurement: NumericResourceMeasurement) {
        currentNumeric = measurement
    }

    fun getBaselineNumeric(): NumericResourceMeasurement? = baselineNumeric

    fun getCurrentNumeric(): NumericResourceMeasurement? = currentNumeric

    companion object {
        @JvmStatic
        val instance: ResourceState = ResourceState()
    }
}
