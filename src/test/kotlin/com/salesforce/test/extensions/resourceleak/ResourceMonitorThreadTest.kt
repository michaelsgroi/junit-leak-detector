package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.TestClock
import com.salesforce.test.extensions.LogAssertingExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ResourceMonitorThreadTest {
    @RegisterExtension
    val logAssertionExtension = LogAssertingExtension(LogAssertingExtension.Level.WARN)

    private lateinit var resourceState: ResourceState
    private lateinit var clock: TestClock

    @AfterEach
    fun cleanup() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        System.clearProperty("resource.leak.detector.polling.interval.milliseconds")
        System.clearProperty("resource.leak.detector.memory.growth.threshold.mb")
        System.clearProperty("test.property.1")
        System.clearProperty("test.property.2")
        System.clearProperty("test.property.leaked")
    }

    @Test
    fun `warns when monitor exceeds per-monitor time budget`() {
        // Polling interval 0 → budget 0 ms; any non-zero monitor elapsed exceeds it.
        // Enables ports + threads + systemprops + envvars + memory; at least one will measure ≥1 ms.
        System.setProperty(
            "resource.leak.detector.monitored.resource.types",
            "ports,threads,systemprops,envvars,memory"
        )
        System.setProperty("resource.leak.detector.polling.interval.milliseconds", "0")
        resourceState = ResourceState()
        clock = TestClock(0L)

        ResourceMonitorThread(resourceState, clock)

        assertTrue(
            logAssertionExtension.logged(LogAssertingExtension.Level.WARN) {
                it.contains("exceeds per-monitor budget")
            },
            "expected a per-monitor time budget warning"
        )
    }

    @Test
    fun `does not warn when monitor stays within time budget`() {
        // 60s polling interval gives ample headroom; SystemPropertyMonitor finishes in microseconds.
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.polling.interval.milliseconds", "60000")
        resourceState = ResourceState()
        clock = TestClock(0L)

        ResourceMonitorThread(resourceState, clock)

        assertTrue(
            !logAssertionExtension.logged(LogAssertingExtension.Level.WARN) {
                it.contains("exceeds per-monitor budget")
            },
            "did not expect a per-monitor time budget warning"
        )
    }

    @Test
    fun `construction fails fast when ddbtables monitor configured but AWS SDK absent`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ddbtables")
        resourceState = ResourceState()
        clock = TestClock(0L)

        val ex = assertThrows(IllegalStateException::class.java) {
            ResourceMonitorThread(resourceState, clock, classPresent = { false })
        }
        val message = ex.message ?: ""
        assertTrue(message.contains("ddbtables"), "expected message to name the monitor: $message")
        assertTrue(
            message.contains("software.amazon.awssdk:dynamodb"),
            "expected message to name the missing dependency: $message"
        )
    }

    @Test
    fun `construction succeeds when ddbtables monitor configured and AWS SDK present`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ddbtables")
        resourceState = ResourceState()
        clock = TestClock(0L)

        ResourceMonitorThread(resourceState, clock, classPresent = { true })
    }

    @Test
    fun `classpath check is not invoked for monitors without optional dependencies`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops,memory,threads")
        resourceState = ResourceState()
        clock = TestClock(0L)

        ResourceMonitorThread(resourceState, clock, classPresent = { false })
    }

    @Test
    fun `thread does not start monitoring when no resource types configured`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        thread.stopMonitoring()
        thread.join(5000)

        val allResources = resourceState.getAllDiscreteResources()
        assertEquals(0, allResources.size)
    }

    @Test
    fun `thread captures baseline when systemprops enabled`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("test.property.1", "value1")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(100)
        thread.stopMonitoring()
        thread.join(5000)

        val allResources = resourceState.getAllDiscreteResources()
        assertTrue(allResources.isNotEmpty())
        val prop1Info = allResources[ResourceId.PropertyId("test.property.1")]
        assertTrue(prop1Info != null)
        assertEquals(true, prop1Info?.isBaseline)
    }

    @Test
    fun `thread detects new properties during polling`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.polling.interval.milliseconds", "100")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(50)
        System.setProperty("test.property.1", "value1")
        Thread.sleep(200)
        thread.stopMonitoring()
        thread.join(5000)

        val allResources = resourceState.getAllDiscreteResources()
        val prop1Info = allResources[ResourceId.PropertyId("test.property.1")]
        assertTrue(prop1Info != null)
        assertEquals(false, prop1Info?.isBaseline)
    }

    @Test
    fun `thread detects property removal during polling`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.polling.interval.milliseconds", "100")
        System.setProperty("test.property.1", "value1")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(200)
        System.clearProperty("test.property.1")
        Thread.sleep(200)
        thread.stopMonitoring()
        thread.join(5000)

        val allResources = resourceState.getAllDiscreteResources()
        val prop1Info = allResources[ResourceId.PropertyId("test.property.1")]
        assertTrue(prop1Info != null)
        assertTrue(prop1Info?.destroyed != null)
    }

    @Test
    fun `thread captures baseline measurement when memory enabled`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "999999")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(100)
        thread.stopMonitoring()
        thread.join(5000)

        val measurements = resourceState.getAllNumericMeasurements()
        assertTrue(measurements.isNotEmpty())
        assertTrue(measurements.first().value > 0)
    }

    @Test
    fun `thread records measurements during polling when memory enabled`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.polling.interval.milliseconds", "100")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "999999")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(350)
        thread.stopMonitoring()
        thread.join(5000)

        val measurements = resourceState.getAllNumericMeasurements()
        assertTrue(measurements.size >= 2, "Expected at least 2 measurements (baseline + polling), got ${measurements.size}")
    }

    @Test
    fun `thread performs final detection on shutdown`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("test.property.1", "value1")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(100)
        System.setProperty("test.property.2", "value2")
        thread.stopMonitoring()
        thread.join(5000)

        val allResources = resourceState.getAllDiscreteResources()
        assertTrue(allResources.containsKey(ResourceId.PropertyId("test.property.2")))
    }

    @Test
    fun `multiple resource types do not interfere with each other during polling`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops,threads")
        System.setProperty("resource.leak.detector.polling.interval.milliseconds", "100")
        System.setProperty("test.property.1", "value1")
        resourceState = ResourceState()
        clock = TestClock(0L)
        val thread = ResourceMonitorThread(resourceState, clock)

        thread.start()
        Thread.sleep(200) // ensure baseline capture completes

        // Add a new property after baseline
        System.setProperty("test.property.leaked", "leaked")
        Thread.sleep(300) // allow at least one poll cycle to detect it

        thread.stopMonitoring()
        thread.join(5000)

        val allResources = resourceState.getAllDiscreteResources()

        // Baseline property should not be destroyed
        val baselineProp = allResources[ResourceId.PropertyId("test.property.1")]
        assertTrue(baselineProp != null, "Baseline property should be tracked")
        assertTrue(baselineProp?.isBaseline == true, "Baseline property should remain baseline")
        assertTrue(baselineProp?.destroyed == null, "Baseline property should not be destroyed")

        // Leaked property should be detected as non-baseline
        val leakedProp = allResources[ResourceId.PropertyId("test.property.leaked")]
        assertTrue(leakedProp != null, "Leaked property should be detected")
        assertEquals(false, leakedProp?.isBaseline, "Leaked property should not be baseline")

        // Threads from baseline should not be destroyed by property monitoring
        val baselineThreads = allResources.filter { (id, info) ->
            id is ResourceId.ThreadId && info.isBaseline
        }
        assertTrue(baselineThreads.isNotEmpty(), "Baseline threads should exist")
        baselineThreads.forEach { (id, info) ->
            assertTrue(info.destroyed == null, "Baseline thread $id should not be destroyed")
        }
    }
}
