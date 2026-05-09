package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.TestClock
import com.salesforce.test.extensions.LogAssertingExtension
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ResourceLeakReporterTest {
    @RegisterExtension
    val logAssertionExtension = LogAssertingExtension(LogAssertingExtension.Level.INFO)

    private lateinit var resourceState: ResourceState
    private lateinit var clock: TestClock

    @BeforeEach
    fun setup() {
        resourceState = ResourceState()
        clock = TestClock(0L)
    }

    @AfterEach
    fun cleanup() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        System.clearProperty("resource.leak.detector.build.failure.resource.types")
        System.clearProperty("resource.leak.detector.memory.growth.threshold.mb")
        logAssertionExtension.shutdown()
    }

    private fun setMonitored(types: String) = System.setProperty("resource.leak.detector.monitored.resource.types", types)

    private fun setBuildFailure(types: String) = System.setProperty("resource.leak.detector.build.failure.resource.types", types)

    // --- system properties ---

    @Test
    fun `does not report system property leaks when not monitored`() {
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, emptySet())
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("leaked")))

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("System Property Leaks") })
    }

    @Test
    fun `reports leaked system properties as current minus baseline`() {
        setMonitored("systemprops")
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("baseline")))
        resourceState.updateCurrentDiscrete(
            ResourceId.PropertyId::class,
            setOf(ResourceId.PropertyId("baseline"), ResourceId.PropertyId("leaked")),
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("System Property Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Property: leaked") })
        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Property: baseline") })
    }

    @Test
    fun `does not report system property header when no leaks`() {
        setMonitored("systemprops")
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("p")))
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("p")))

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("System Property Leaks") })
    }

    // --- env vars ---

    @Test
    fun `reports leaked environment variables`() {
        setMonitored("envvars")
        resourceState.recordBaselineDiscrete(ResourceId.EnvironmentVariableId::class, emptySet())
        resourceState.updateCurrentDiscrete(
            ResourceId.EnvironmentVariableId::class,
            setOf(ResourceId.EnvironmentVariableId("LEAKED_VAR")),
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Environment Variable Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Variable: LEAKED_VAR") })
    }

    // --- threads ---

    @Test
    fun `reports leaked threads`() {
        setMonitored("threads")
        resourceState.recordBaselineDiscrete(ResourceId.ThreadId::class, emptySet())
        resourceState.updateCurrentDiscrete(
            ResourceId.ThreadId::class,
            setOf(ResourceId.ThreadId("worker", 42L)),
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread: worker (ID: 42)") })
    }

    // --- ports ---

    @Test
    fun `reports leaked ports`() {
        setMonitored("ports")
        resourceState.recordBaselineDiscrete(ResourceId.PortId::class, emptySet())
        resourceState.updateCurrentDiscrete(ResourceId.PortId::class, setOf(ResourceId.PortId(8080)))

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port: 8080") })
    }

    // --- ddb tables ---

    @Test
    fun `reports leaked ddb tables`() {
        setMonitored("ddbtables")
        resourceState.recordBaselineDiscrete(ResourceId.DynamoDbTableId::class, emptySet())
        resourceState.updateCurrentDiscrete(
            ResourceId.DynamoDbTableId::class,
            setOf(ResourceId.DynamoDbTableId("orders")),
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("DynamoDB Table Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Table: orders") })
    }

    // --- memory ---

    @Test
    fun `reports memory leak when growth exceeds threshold`() {
        setMonitored("memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "100")
        resourceState.recordBaselineNumeric(NumericResourceMeasurement(256L * BYTES_PER_MB, clock.instant()))
        clock.advanceMillis(10)
        resourceState.updateCurrentNumeric(NumericResourceMeasurement(512L * BYTES_PER_MB, clock.instant()))

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Memory Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Baseline: 256 MB") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Final: 512 MB") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Increase: 256 MB") })
    }

    @Test
    fun `does not report memory leak when growth within threshold`() {
        setMonitored("memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "500")
        resourceState.recordBaselineNumeric(NumericResourceMeasurement(256L * BYTES_PER_MB, clock.instant()))
        resourceState.updateCurrentNumeric(NumericResourceMeasurement(356L * BYTES_PER_MB, clock.instant()))

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Memory Leaks") })
    }

    // --- build failure ---

    @Test
    fun `build failure not triggered when build failure resource types not configured`() {
        setMonitored("systemprops")
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, emptySet())
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("leaked")))

        var triggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { triggered = true }).report()

        assertFalse(triggered)
    }

    @Test
    fun `build failure triggered for matching discrete leak`() {
        setMonitored("systemprops")
        setBuildFailure("systemprops")
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, emptySet())
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("leaked")))

        var triggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { triggered = true }).report()

        assertTrue(triggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("systemprops") })
    }

    @Test
    fun `build failure not triggered when leak resource type not in build failure list`() {
        setMonitored("systemprops")
        setBuildFailure("ports")
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, emptySet())
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, setOf(ResourceId.PropertyId("leaked")))

        var triggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { triggered = true }).report()

        assertFalse(triggered)
    }

    @Test
    fun `build failure triggered for memory growth above threshold`() {
        setMonitored("memory")
        setBuildFailure("memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "100")
        resourceState.recordBaselineNumeric(NumericResourceMeasurement(256L * BYTES_PER_MB, clock.instant()))
        resourceState.updateCurrentNumeric(NumericResourceMeasurement(512L * BYTES_PER_MB, clock.instant()))

        var triggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { triggered = true }).report()

        assertTrue(triggered)
    }

    @Test
    fun `build failure not triggered for memory growth within threshold`() {
        setMonitored("memory")
        setBuildFailure("memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "500")
        resourceState.recordBaselineNumeric(NumericResourceMeasurement(256L * BYTES_PER_MB, clock.instant()))
        resourceState.updateCurrentNumeric(NumericResourceMeasurement(356L * BYTES_PER_MB, clock.instant()))

        var triggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { triggered = true }).report()

        assertFalse(triggered)
    }

    @Test
    fun `build failure not triggered for baseline-only resources`() {
        setMonitored("systemprops")
        setBuildFailure("systemprops")
        val baseline = setOf<ResourceId>(ResourceId.PropertyId("baseline.property"))
        resourceState.recordBaselineDiscrete(ResourceId.PropertyId::class, baseline)
        resourceState.updateCurrentDiscrete(ResourceId.PropertyId::class, baseline)

        var triggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { triggered = true }).report()

        assertFalse(triggered)
    }

    companion object {
        private const val BYTES_PER_MB = 1_048_576L
    }
}
