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
        val configuration = Configuration.instance
        val r = registry

        // Run suite-shared shutdown hooks (e.g., SpringContextShutdownHook) so threads
        // and ports owned by cached infrastructure get released before the FINAL
        // snapshot. Anything they release naturally drops out of the leak list because
        // attribution treats "absent at FINAL" as not-a-leak.
        SuiteShutdownRunner().runAll()

        // Drain async releases triggered by the hooks (Tomcat connector close, scheduler
        // shutdown, connection pool drain, etc.) before snapshotting. The snapshot
        // itself is what attribution operates on; if a released thread is still in the
        // FINAL snapshot, it's a real leak.
        if (configuration.finalSettleEnabled && r != null) {
            val waiter = PreclassSettleWaiter.forFinal(configuration)
            // Treat all currently-alive threads/ports as the "carry-over set" the wait
            // is draining — we want everything that's going to die from the hooks to
            // die before we snapshot.
            val current = r.probeDiscrete(setOf(ResourceType.THREADS, ResourceType.PORTS))
            waiter.waitForSettle(previousClassDelta = current) { types -> r.probeDiscrete(types) }
        }

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
