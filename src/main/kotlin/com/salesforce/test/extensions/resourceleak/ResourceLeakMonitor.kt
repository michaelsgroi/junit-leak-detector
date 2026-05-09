package com.salesforce.test.extensions.resourceleak

import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import org.slf4j.LoggerFactory

class ResourceLeakMonitor : TestExecutionListener {
    private var registry: MonitorRegistry? = null
    private val log = LoggerFactory.getLogger(javaClass)

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        try {
            val r = MonitorRegistry()
            registry = r
            r.captureBaseline()
            SharedMonitorRegistry.set(r)
        } catch (e: IllegalStateException) {
            // JUnit Platform swallows exceptions from this method, so we exit
            // explicitly to ensure a fatal startup error fails the build.
            log.error("Resource leak detector failed to start: {}", e.message)
            System.exit(1)
        }
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        registry?.snapshotAll()
        SharedMonitorRegistry.clear()
        ResourceLeakReporter().report()
    }
}

internal object SharedMonitorRegistry {
    @Volatile
    private var current: MonitorRegistry? = null

    fun set(registry: MonitorRegistry) {
        current = registry
    }

    fun clear() {
        current = null
    }

    fun get(): MonitorRegistry? = current
}
