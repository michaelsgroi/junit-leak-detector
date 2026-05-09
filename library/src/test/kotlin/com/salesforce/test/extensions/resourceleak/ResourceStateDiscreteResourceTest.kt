package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ResourceStateDiscreteResourceTest {
    private lateinit var resourceState: ResourceState
    private lateinit var clock: TestClock

    @BeforeEach
    fun setup() {
        resourceState = ResourceState()
        clock = TestClock(0L)
    }

    @Test
    fun `recordBaselineDiscrete sets both baseline and current`() {
        val resources =
            setOf<ResourceId>(
                ResourceId.PropertyId("prop1"),
                ResourceId.PropertyId("prop2"),
            )

        resourceState.recordBaselineDiscrete(ResourceType.SYSTEM_PROPS, resources)

        assertEquals(resources, resourceState.getBaselineDiscrete(ResourceType.SYSTEM_PROPS))
        assertEquals(resources, resourceState.getCurrentDiscrete(ResourceType.SYSTEM_PROPS))
    }

    @Test
    fun `updateCurrentDiscrete replaces current without affecting baseline`() {
        val baseline = setOf<ResourceId>(ResourceId.PropertyId("prop1"))
        resourceState.recordBaselineDiscrete(ResourceType.SYSTEM_PROPS, baseline)

        val updated =
            setOf<ResourceId>(
                ResourceId.PropertyId("prop1"),
                ResourceId.PropertyId("leaked"),
            )
        resourceState.updateCurrentDiscrete(ResourceType.SYSTEM_PROPS, updated)

        assertEquals(baseline, resourceState.getBaselineDiscrete(ResourceType.SYSTEM_PROPS))
        assertEquals(updated, resourceState.getCurrentDiscrete(ResourceType.SYSTEM_PROPS))
    }

    @Test
    fun `update for one type does not affect other types`() {
        val properties = setOf<ResourceId>(ResourceId.PropertyId("prop1"))
        val threads = setOf<ResourceId>(ResourceId.ThreadId("main", 1))
        resourceState.recordBaselineDiscrete(ResourceType.SYSTEM_PROPS, properties)
        resourceState.recordBaselineDiscrete(ResourceType.THREADS, threads)

        resourceState.updateCurrentDiscrete(ResourceType.SYSTEM_PROPS, emptySet())

        assertEquals(emptySet<ResourceId>(), resourceState.getCurrentDiscrete(ResourceType.SYSTEM_PROPS))
        assertEquals(threads, resourceState.getCurrentDiscrete(ResourceType.THREADS))
    }

    @Test
    fun `getCurrentDiscrete returns empty set for unknown type`() {
        assertEquals(emptySet<ResourceId>(), resourceState.getCurrentDiscrete(ResourceType.PORTS))
    }

    @Test
    fun `recordBaselineNumeric sets both baseline and current`() {
        val measurement = NumericResourceMeasurement(1024L, clock.instant())

        resourceState.recordBaselineNumeric(measurement)

        assertEquals(measurement, resourceState.getBaselineNumeric())
        assertEquals(measurement, resourceState.getCurrentNumeric())
    }

    @Test
    fun `updateCurrentNumeric replaces current without affecting baseline`() {
        val baseline = NumericResourceMeasurement(1024L, clock.instant())
        resourceState.recordBaselineNumeric(baseline)
        clock.advanceMillis(10)
        val updated = NumericResourceMeasurement(2048L, clock.instant())

        resourceState.updateCurrentNumeric(updated)

        assertEquals(baseline, resourceState.getBaselineNumeric())
        assertEquals(updated, resourceState.getCurrentNumeric())
    }

    @Test
    fun `numeric getters return null when never recorded`() {
        assertNull(resourceState.getBaselineNumeric())
        assertNull(resourceState.getCurrentNumeric())
    }

    @Test
    fun `recordTestClassStart and end populate lifecycle map`() {
        val start = clock.instant()
        resourceState.recordTestClassStart("com.example.A", start)
        clock.advanceMillis(5)
        val end = clock.instant()
        resourceState.recordTestClassEnd("com.example.A", end)

        val lifecycles = resourceState.getAllTestClassLifecycles()
        assertEquals(TestClassLifecycle(start, end), lifecycles["com.example.A"])
    }

    @Test
    fun `recordTestClassEnd is a no-op for unknown class`() {
        resourceState.recordTestClassEnd("com.example.Missing", clock.instant())
        assertEquals(emptyMap<TestClassName, TestClassLifecycle>(), resourceState.getAllTestClassLifecycles())
    }
}
