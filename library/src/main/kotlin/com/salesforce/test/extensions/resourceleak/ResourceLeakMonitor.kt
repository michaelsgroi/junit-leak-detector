package com.salesforce.test.extensions.resourceleak

import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Clock

class ResourceLeakMonitor : TestExecutionListener {
    private var registry: MonitorRegistry? = null
    private var rawReportWriter: RawReportWriter? = null
    private var reportPaths: ReportPaths? = null
    private val clock: Clock = Clock.systemUTC()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        try {
            val configuration = Configuration.instance
            val startedAt = clock.instant()
            val paths =
                ReportPaths(
                    outputDir = File(configuration.reportOutputDir),
                    timestamp = ReportPaths.timestamp(startedAt),
                )
            reportPaths = paths
            paths.outputDir.mkdirs()
            ForkDetector(markerDirectory = File(paths.outputDir, "forks")).checkAndRecordFork()
            val writer = RawReportWriter(paths.rawReport)
            rawReportWriter = writer
            val r = MonitorRegistry(rawReportWriter = writer)
            registry = r
            writer.open(
                startedAt = startedAt,
                monitors =
                    configuration.monitoredResourceTypes
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                snapshotGranularity = configuration.snapshotGranularity,
            )
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
        registry?.snapshotAll(kind = SnapshotKind.FINAL)
        SharedMonitorRegistry.clear()
        rawReportWriter?.closeWith(
            finishedAt = clock.instant(),
            lifecycles = ResourceState.instance.getAllTestClassLifecycles(),
        )
        reportPaths?.let { AttributionRunner(reportPaths = it).runInline() }
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
