package com.michaelsgroi.test.extensions.resourceleak

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Clock

/**
 * The single integration point with JUnit. Handles suite lifecycle (start/finish) and
 * per-class / per-method snapshots — replacing the now-removed
 * `ResourceLeakMonitorTestLifecycleExtension`. Listening at the platform level (rather
 * than the Jupiter extension level) is what lets consumers skip
 * `junit.jupiter.extensions.autodetection.enabled=true` in their Surefire config.
 */
class ResourceLeakMonitor(
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC(),
    private val configuration: Configuration = Configuration.instance,
    private val settleWaiter: PreclassSettleWaiter = PreclassSettleWaiter.forPreclass(configuration),
) : TestExecutionListener {
    private var registry: MonitorRegistry? = null
    private var rawReportWriter: RawReportWriter? = null
    private var reportPaths: ReportPaths? = null
    private var threadCreationRecorder: ThreadCreationRecorder? = null
    private val log = LoggerFactory.getLogger(javaClass)

    // Settle-wait state, scoped to one suite run (single listener instance per test plan).
    private var previousClassDelta: Map<ResourceType, Set<ResourceId>>? = null
    private var currentClassBeforeAllProbe: Map<ResourceType, Set<ResourceId>>? = null

    private var disabled: Boolean = false

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        // Master kill switch: short-circuit before any side-effects (no fork marker,
        // no report directory, no monitors). Allows consumers to keep the dep on the
        // classpath but turn the detector off entirely.
        if (configuration.disabled) {
            disabled = true
            return
        }
        try {
            val startedAt = clock.instant()
            val paths =
                ReportPaths(
                    outputDir = File(configuration.reportOutputDir),
                    timestamp = ReportPaths.timestamp(startedAt),
                )
            reportPaths = paths
            paths.outputDir.mkdirs()
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
                memoryGrowthThresholdBytes = configuration.memoryGrowthThresholdMb * BYTES_PER_MB,
            )
            r.captureBaseline()
            // Start JFR recording for thread-creation tracking. Must be after baseline so
            // the recording captures threads spawned during test execution rather than
            // baseline-time JVM internals.
            if (configuration.threadCreationTrackingEnabled) {
                ThreadCreationRecorder(
                    outputFile = paths.threadCreations,
                    stackDepth = configuration.threadCreationStackDepth,
                ).also {
                    it.start()
                    threadCreationRecorder = it
                }
            }
        } catch (e: IllegalStateException) {
            // JUnit Platform swallows exceptions from this method, so we exit
            // explicitly to ensure a fatal startup error fails the build.
            log.error("Resource leak detector failed to start: {}", e.message)
            System.exit(1)
        }
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (disabled) return
        classNameOf(testIdentifier)?.let { onClassStart(it) }
        methodKeyOf(testIdentifier)?.let { onMethodStart(it) }
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        if (disabled) return
        // Tear-down order mirrors construction order: per-method first (it's nested inside per-class).
        methodKeyOf(testIdentifier)?.let { onMethodEnd(it) }
        classNameOf(testIdentifier)?.let { onClassEnd(it) }
    }

    override fun executionSkipped(
        testIdentifier: TestIdentifier,
        reason: String?,
    ) {
        // Skipped descriptors never get executionStarted, so we never recorded a class start.
        // Nothing to clean up.
    }

    private fun onClassStart(testClassName: String) {
        val r = registry
        if (configuration.preclassSettleEnabled && r != null) {
            previousClassDelta?.let { delta ->
                settleWaiter.waitForSettle(delta) { types -> r.probeDiscrete(types) }
            }
        }
        resourceState.recordTestClassStart(testClassName, clock.instant())
        if (configuration.preclassSettleEnabled && r != null) {
            currentClassBeforeAllProbe = r.probeDiscrete(SETTLE_TYPES)
        }
        r?.probeMemoryAfterGc()
        r?.snapshotAll(kind = SnapshotKind.BEFORE_ALL, testClass = testClassName)
    }

    private fun onClassEnd(testClassName: String) {
        val r = registry
        r?.probeMemoryAfterGc()
        r?.snapshotAll(kind = SnapshotKind.AFTER_ALL, testClass = testClassName)
        resourceState.recordTestClassEnd(testClassName, clock.instant())
        if (configuration.preclassSettleEnabled && r != null) {
            val before = currentClassBeforeAllProbe ?: emptyMap()
            val after = r.probeDiscrete(SETTLE_TYPES)
            previousClassDelta = computeDelta(before, after)
            currentClassBeforeAllProbe = null
        }
    }

    private fun onMethodStart(key: TestMethodKey) {
        if (configuration.snapshotGranularity != SnapshotGranularity.TEST) return
        resourceState.recordTestMethodStart(key, clock.instant())
        registry?.snapshotAll(
            kind = SnapshotKind.BEFORE_EACH,
            testClass = key.testClassName,
            testMethod = key.testMethodName,
        )
    }

    private fun onMethodEnd(key: TestMethodKey) {
        if (configuration.snapshotGranularity != SnapshotGranularity.TEST) return
        registry?.snapshotAll(
            kind = SnapshotKind.AFTER_EACH,
            testClass = key.testClassName,
            testMethod = key.testMethodName,
        )
        resourceState.recordTestMethodEnd(key, clock.instant())
    }

    /**
     * Returns the FQ class name when [testIdentifier] is a class-boundary container.
     * The spike at `docs/listener-migration-spike.md` validated that all six Jupiter test
     * kinds (plain, parameterized, factory, nested, repeated, PER_CLASS) emit a single
     * `Container + ClassSource` pair bracketing the test class's full execution.
     */
    private fun classNameOf(testIdentifier: TestIdentifier): String? {
        if (!testIdentifier.isContainer) return null
        val source = testIdentifier.source.orElse(null) as? ClassSource ?: return null
        return source.className
    }

    /**
     * Returns a `(class, method)` key when [testIdentifier] is a per-test-method leaf.
     * `Test + MethodSource` covers all method-level events across plain, parameterized
     * (`test-template-invocation:`), factory (`dynamic-test:`), nested, and repeated
     * (`test-template-invocation:`) — see the spike for details.
     */
    private fun methodKeyOf(testIdentifier: TestIdentifier): TestMethodKey? {
        if (!testIdentifier.isTest) return null
        val source = testIdentifier.source.orElse(null) as? MethodSource ?: return null
        return TestMethodKey(source.className, source.methodName)
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        if (disabled) return
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
        r?.memoryMonitor()?.let { mem ->
            log.info(
                "MemoryMonitor: total GC time {} ms across {} invocations",
                mem.totalGcDuration.toMillis(),
                mem.gcInvocationCount,
            )
        }
        // Stop JFR after FINAL snapshot so the recording captures every thread created
        // during the test plan up to (and including) leaks visible in the snapshot.
        threadCreationRecorder?.stopAndDump()
        rawReportWriter?.closeWith(
            finishedAt = clock.instant(),
            lifecycles = resourceState.getAllTestClassLifecycles(),
        )
        reportPaths?.let { AttributionRunner(reportPaths = it).runInline() }
    }

    private fun computeDelta(
        before: Map<ResourceType, Set<ResourceId>>,
        after: Map<ResourceType, Set<ResourceId>>,
    ): Map<ResourceType, Set<ResourceId>> {
        val out = mutableMapOf<ResourceType, Set<ResourceId>>()
        for ((type, afterIds) in after) {
            val beforeIds = before[type] ?: emptySet()
            val appeared = afterIds - beforeIds
            if (appeared.isNotEmpty()) out[type] = appeared
        }
        return out
    }

    companion object {
        private val SETTLE_TYPES = setOf(ResourceType.THREADS, ResourceType.PORTS)
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
