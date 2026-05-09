package com.salesforce.test.extensions.resourceleak

import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import org.slf4j.LoggerFactory

class ResourceLeakMonitor : TestExecutionListener {
    private var monitorThread: ResourceMonitorThread? = null
    private val log = LoggerFactory.getLogger(javaClass)

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        try {
            monitorThread = ResourceMonitorThread()
        } catch (e: IllegalStateException) {
            // JUnit Platform swallows exceptions from this method, so we exit
            // explicitly to ensure a fatal startup error fails the build.
            log.error("Resource leak detector failed to start: {}", e.message)
            System.exit(1)
        }
        monitorThread?.start()
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        monitorThread?.stopMonitoring()
    }
}
