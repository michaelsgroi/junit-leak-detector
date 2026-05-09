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
        val resources = setOf<ResourceId>(
            ResourceId.PropertyId("prop1"),
            ResourceId.PropertyId("prop2")
        )

        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, resources)

        assertEquals(resources, resourceState.getBaselineDiscrete(ResourceId.PropertyId::class))
        assertEquals(resources, resourceState.getCurrentDiscrete(ResourceId.PropertyId::class))
    }

    @Test
    fun `updateCurrentDiscrete replaces current without affecting baseline`() {
        val baseline = setOf<ResourceId>(ResourceId.PropertyId("prop1"))
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, baseline)

        val updated = setOf<ResourceId>(
            ResourceId.PropertyId("prop1"),
            ResourceId.PropertyId("leaked")
        )
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, updated)

        assertEquals(baseline, resourceState.getBaselineDiscrete(ResourceId.PropertyId::class))
        assertEquals(updated, resourceState.getCurrentDiscrete(ResourceId.PropertyId::class))
    }

    @Test
    fun `update for one type does not affect other types`() {
        val properties = setOf<ResourceId>(ResourceId.PropertyId("prop1"))
        val threads = setOf<ResourceId>(ResourceId.ThreadId("main", 1))
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, properties)
        resourceState.recordBaselineDiscrete(ResourceId.ThreadId::class, threads)

        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, emptySet())

        assertEquals(emptySet<ResourceId>(), resourceState.getCurrentDiscrete(ResourceId.PropertyId::class))
        assertEquals(threads, resourceState.getCurrentDiscrete(ResourceId.ThreadId::class))
    }

    @Test
    fun `getCurrentDiscrete returns empty set for unknown type`() {
        assertEquals(emptySet<ResourceId>(), resourceState.getCurrentDiscrete(ResourceId.PortId::class))
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
