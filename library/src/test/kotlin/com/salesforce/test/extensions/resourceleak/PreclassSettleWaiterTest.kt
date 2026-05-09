package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.extensions.LogAssertingExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Properties

class PreclassSettleWaiterTest {
    @RegisterExtension
    val logExt = LogAssertingExtension(LogAssertingExtension.Level.WARN)

    private fun config(
        maxSeconds: Long = 10L,
        pollSeconds: Long = 1L,
    ): Configuration {
        val props =
            Properties().apply {
                setProperty("preclass.settle.enabled", "true")
                setProperty("preclass.settle.max.seconds", maxSeconds.toString())
                setProperty("preclass.settle.poll.interval.seconds", pollSeconds.toString())
            }
        return Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
    }

    private fun cannedProbe(probes: List<Map<ResourceType, Set<ResourceId>>>): (Set<ResourceType>) -> Map<ResourceType, Set<ResourceId>> {
        var index = 0
        return { _ -> probes[index++.coerceAtMost(probes.size - 1)] }
    }

    @Test
    fun `empty delta is a no-op`() {
        var sleepCount = 0
        PreclassSettleWaiter(config(), sleeper = { sleepCount++ })
            .waitForSettle(emptyMap(), cannedProbe(listOf(emptyMap())))
        assertEquals(0, sleepCount)
    }

    @Test
    fun `returns immediately when carry-over is empty on first probe`() {
        val delta = mapOf(ResourceType.THREADS to setOf<ResourceId>(ResourceId.ThreadId("worker", 1)))
        var sleepCount = 0
        PreclassSettleWaiter(config(), sleeper = { sleepCount++ })
            .waitForSettle(delta, cannedProbe(listOf(emptyMap())))
        assertEquals(0, sleepCount)
    }

    @Test
    fun `polls until carry-over clears`() {
        val tid: ResourceId = ResourceId.ThreadId("worker", 1)
        val delta = mapOf(ResourceType.THREADS to setOf(tid))
        val probes =
            listOf(
                mapOf(ResourceType.THREADS to setOf(tid)),
                mapOf(ResourceType.THREADS to setOf(tid)),
                mapOf(ResourceType.THREADS to emptySet()),
            )
        var sleepCount = 0
        var fakeNow = 0L
        PreclassSettleWaiter(
            config(maxSeconds = 60L, pollSeconds = 1L),
            sleeper = {
                sleepCount++
                fakeNow += it
            },
            nowMillis = { fakeNow },
        ).waitForSettle(delta, cannedProbe(probes))
        assertEquals(2, sleepCount)
    }

    @Test
    fun `times out and warns when carry-over still present after max wait`() {
        val tid: ResourceId = ResourceId.ThreadId("stuck", 99)
        val delta = mapOf(ResourceType.THREADS to setOf(tid))
        val stuckProbe = mapOf(ResourceType.THREADS to setOf(tid))
        var fakeNow = 0L
        PreclassSettleWaiter(
            config(maxSeconds = 2L, pollSeconds = 1L),
            sleeper = { fakeNow += it },
            nowMillis = { fakeNow },
        ).waitForSettle(delta, cannedProbe(List(20) { stuckProbe }))
        assertTrue(logExt.logged(LogAssertingExtension.Level.WARN) { it.contains("max wait") })
    }

    @Test
    fun `non-applicable resource types in delta are ignored`() {
        // System properties should not trigger the wait even if present in the delta.
        val delta = mapOf(ResourceType.SYSTEM_PROPS to setOf<ResourceId>(ResourceId.PropertyId("x")))
        var sleepCount = 0
        PreclassSettleWaiter(config(), sleeper = { sleepCount++ })
            .waitForSettle(delta, cannedProbe(listOf(emptyMap())))
        assertEquals(0, sleepCount)
    }
}
