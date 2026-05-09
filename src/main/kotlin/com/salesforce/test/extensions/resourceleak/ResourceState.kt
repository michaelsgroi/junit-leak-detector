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
    private val baselineDiscrete = mutableMapOf<KClass<out ResourceId>, Set<ResourceId>>()
    private val currentDiscrete = mutableMapOf<KClass<out ResourceId>, Set<ResourceId>>()
    private var baselineNumeric: NumericResourceMeasurement? = null
    private var currentNumeric: NumericResourceMeasurement? = null

    fun recordTestClassStart(testClassName: TestClassName, startTimestamp: Instant) {
        testClassLifecycles[testClassName] = TestClassLifecycle(start = startTimestamp, end = startTimestamp)
    }

    fun recordTestClassEnd(testClassName: TestClassName, endTimestamp: Instant) {
        val existing = testClassLifecycles[testClassName] ?: return
        testClassLifecycles[testClassName] = existing.copy(end = endTimestamp)
    }

    fun getAllTestClassLifecycles(): Map<TestClassName, TestClassLifecycle> = testClassLifecycles.toMap()

    fun recordBaselineDiscrete(resourceIdType: KClass<out ResourceId>, resources: Set<ResourceId>) {
        baselineDiscrete[resourceIdType] = resources.toSet()
        currentDiscrete[resourceIdType] = resources.toSet()
    }

    fun updateCurrentDiscrete(resourceIdType: KClass<out ResourceId>, resources: Set<ResourceId>) {
        currentDiscrete[resourceIdType] = resources.toSet()
    }

    fun getBaselineDiscrete(resourceIdType: KClass<out ResourceId>): Set<ResourceId> =
        baselineDiscrete[resourceIdType].orEmpty()

    fun getCurrentDiscrete(resourceIdType: KClass<out ResourceId>): Set<ResourceId> =
        currentDiscrete[resourceIdType].orEmpty()

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
