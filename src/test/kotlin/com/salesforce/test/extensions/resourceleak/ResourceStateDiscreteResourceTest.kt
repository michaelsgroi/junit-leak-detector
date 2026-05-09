package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `recordBaselineResources records resources with baseline flag`() {
        val timestamp = clock.instant()
        val resources = setOf(
            ResourceId.PropertyId("prop1"),
            ResourceId.PropertyId("prop2")
        )

        resourceState.recordBaselineResources(resources, timestamp)

        val allResources = resourceState.getAllDiscreteResources()
        assertEquals(2, allResources.size)
        resources.forEach { resourceId ->
            val info = allResources[resourceId]
            assertEquals(timestamp, info?.first)
            assertEquals(timestamp, info?.last)
            assertNull(info?.destroyed)
            assertEquals(true, info?.isBaseline)
        }
    }

    @Test
    fun `updateDiscreteResources records newly detected resources`() {
        val timestamp1 = clock.instant()
        val baseline = setOf<ResourceId>(ResourceId.PropertyId("baseline1"))
        resourceState.recordBaselineResources(baseline, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        val current = setOf(
            ResourceId.PropertyId("baseline1"),
            ResourceId.PropertyId("new1")
        )
        resourceState.updateDiscreteResources(current, timestamp2)

        val allResources = resourceState.getAllDiscreteResources()
        assertEquals(2, allResources.size)
        val newInfo = allResources[ResourceId.PropertyId("new1")]
        assertEquals(timestamp2, newInfo?.first)
        assertEquals(timestamp2, newInfo?.last)
        assertNull(newInfo?.destroyed)
        assertEquals(false, newInfo?.isBaseline)
    }

    @Test
    fun `updateDiscreteResources marks destroyed resources`() {
        val timestamp1 = clock.instant()
        val baseline = setOf(ResourceId.PropertyId("prop1"))
        resourceState.recordBaselineResources(baseline, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        val current = emptySet<ResourceId>()
        resourceState.updateDiscreteResources(current, timestamp2)

        val allResources = resourceState.getAllDiscreteResources()
        val info = allResources[ResourceId.PropertyId("prop1")]
        assertEquals(timestamp2, info?.destroyed)
    }

    @Test
    fun `updateDiscreteResources updates last detected time for active resources`() {
        val timestamp1 = clock.instant()
        val baseline = setOf(ResourceId.PropertyId("prop1"))
        resourceState.recordBaselineResources(baseline, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        resourceState.updateDiscreteResources(baseline, timestamp2)

        clock.advanceMillis(10)
        val timestamp3 = clock.instant()
        resourceState.updateDiscreteResources(baseline, timestamp3)

        val allResources = resourceState.getAllDiscreteResources()
        val info = allResources[ResourceId.PropertyId("prop1")]
        assertEquals(timestamp1, info?.first)
        assertEquals(timestamp3, info?.last)
        assertNull(info?.destroyed)
    }

    @Test
    fun `updateDiscreteResources clears destroyed time when resource is recreated`() {
        val timestamp1 = clock.instant()
        val baseline = setOf(ResourceId.PropertyId("prop1"))
        resourceState.recordBaselineResources(baseline, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        resourceState.updateDiscreteResources(emptySet(), timestamp2)

        clock.advanceMillis(10)
        val timestamp3 = clock.instant()
        val recreated = resourceState.updateDiscreteResources(baseline, timestamp3)

        val allResources = resourceState.getAllDiscreteResources()
        val info = allResources[ResourceId.PropertyId("prop1")]
        assertEquals(timestamp1, info?.first)
        assertEquals(timestamp3, info?.last)
        assertNull(info?.destroyed)
        assertTrue(recreated.contains(ResourceId.PropertyId("prop1")))
    }

    @Test
    fun `updateDiscreteResources with resourceIdType does not destroy resources of other types`() {
        val timestamp1 = clock.instant()
        val properties = setOf(ResourceId.PropertyId("prop1"))
        val threads = setOf(ResourceId.ThreadId("main", 1))
        resourceState.recordBaselineResources(properties, timestamp1)
        resourceState.recordBaselineResources(threads, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        // Update only properties - threads should NOT be affected
        resourceState.updateDiscreteResources(properties, timestamp2, ResourceId.PropertyId::class)

        val allResources = resourceState.getAllDiscreteResources()
        val threadInfo = allResources[ResourceId.ThreadId("main", 1)]
        assertNull(threadInfo?.destroyed, "Thread should not be destroyed when updating properties")
    }

    @Test
    fun `updateDiscreteResources without resourceIdType destroys resources of all types`() {
        val timestamp1 = clock.instant()
        val properties = setOf(ResourceId.PropertyId("prop1"))
        val threads = setOf(ResourceId.ThreadId("main", 1))
        resourceState.recordBaselineResources(properties, timestamp1)
        resourceState.recordBaselineResources(threads, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        // Update with only properties, no type filter - threads WILL be destroyed (legacy behavior)
        resourceState.updateDiscreteResources(properties, timestamp2)

        val allResources = resourceState.getAllDiscreteResources()
        val threadInfo = allResources[ResourceId.ThreadId("main", 1)]
        assertTrue(threadInfo?.destroyed != null, "Thread should be destroyed without type filter")
    }

    @Test
    fun `updateDiscreteResources with resourceIdType detects new resources of that type`() {
        val timestamp1 = clock.instant()
        val properties = setOf(ResourceId.PropertyId("prop1"))
        val threads = setOf(ResourceId.ThreadId("main", 1))
        resourceState.recordBaselineResources(properties, timestamp1)
        resourceState.recordBaselineResources(threads, timestamp1)

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        val updatedProperties = setOf(
            ResourceId.PropertyId("prop1"),
            ResourceId.PropertyId("leaked-prop")
        )
        resourceState.updateDiscreteResources(updatedProperties, timestamp2, ResourceId.PropertyId::class)

        val allResources = resourceState.getAllDiscreteResources()
        val leakedInfo = allResources[ResourceId.PropertyId("leaked-prop")]
        assertEquals(false, leakedInfo?.isBaseline)
        assertNull(leakedInfo?.destroyed)
        // Thread should be unaffected
        val threadInfo = allResources[ResourceId.ThreadId("main", 1)]
        assertNull(threadInfo?.destroyed)
    }

    @Test
    fun `updateDiscreteResources with empty set and resourceIdType only destroys that type`() {
        val timestamp1 = clock.instant()
        resourceState.recordBaselineResources(
            setOf(ResourceId.PropertyId("prop1")),
            timestamp1
        )
        resourceState.recordBaselineResources(
            setOf(ResourceId.ThreadId("main", 1)),
            timestamp1
        )
        resourceState.recordBaselineResources(
            setOf(ResourceId.PortId(8080)),
            timestamp1
        )

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        // Destroy all properties
        resourceState.updateDiscreteResources(emptySet(), timestamp2, ResourceId.PropertyId::class)

        val allResources = resourceState.getAllDiscreteResources()
        assertTrue(allResources[ResourceId.PropertyId("prop1")]?.destroyed != null)
        assertNull(allResources[ResourceId.ThreadId("main", 1)]?.destroyed)
        assertNull(allResources[ResourceId.PortId(8080)]?.destroyed)
    }

    @Test
    fun `multiple resource types updated independently do not interfere`() {
        val timestamp1 = clock.instant()
        resourceState.recordBaselineResources(
            setOf(ResourceId.PropertyId("baseline-prop")),
            timestamp1
        )
        resourceState.recordBaselineResources(
            setOf(ResourceId.ThreadId("baseline-thread", 1)),
            timestamp1
        )

        clock.advanceMillis(10)
        val timestamp2 = clock.instant()
        // Simulate what ResourceMonitorThread does: update each type separately
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("baseline-prop"), ResourceId.PropertyId("leaked-prop")),
            timestamp2,
            ResourceId.PropertyId::class
        )
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId("baseline-thread", 1), ResourceId.ThreadId("leaked-thread", 2)),
            timestamp2,
            ResourceId.ThreadId::class
        )

        val allResources = resourceState.getAllDiscreteResources()
        assertEquals(4, allResources.size)
        // Baseline resources still present and not destroyed
        assertNull(allResources[ResourceId.PropertyId("baseline-prop")]?.destroyed)
        assertNull(allResources[ResourceId.ThreadId("baseline-thread", 1)]?.destroyed)
        // Leaked resources detected as non-baseline
        assertFalse(allResources[ResourceId.PropertyId("leaked-prop")]?.isBaseline ?: true)
        assertFalse(allResources[ResourceId.ThreadId("leaked-thread", 2)]?.isBaseline ?: true)
    }
}
