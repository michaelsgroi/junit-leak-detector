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
        System.clearProperty("resource.leak.detector.thread.grace.period.seconds")
        System.clearProperty("test.property.leaked")
        logAssertionExtension.shutdown()
    }

    @Test
    fun `report does not report system property leaks when not monitored`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(10)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("System Property Leaks") })
    }

    @Test
    fun `report detects and reports leaked system properties`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(10)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("System Property Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("test.property.leaked") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(leakedTimestamp.toString()) })
    }

    @Test
    fun `report excludes baseline properties from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        val timestamp = clock.instant()
        val baselineProp = ResourceId.PropertyId("baseline.property")
        resourceState.recordBaselineResources(setOf(baselineProp), timestamp)
        clock.advanceMillis(10)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(baselineProp, ResourceId.PropertyId("test.property.leaked")),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("System Property Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("test.property.leaked") })
        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("baseline.property") && it.contains("Property:") })
    }

    @Test
    fun `report excludes destroyed properties from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(10)
        val createdTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.temporary")),
            createdTimestamp
        )
        clock.advanceMillis(10)
        val destroyedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(emptySet(), destroyedTimestamp)

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("test.property.temporary") && it.contains("Property:") })
    }

    @Test
    fun `report attributes leaks to candidate test classes`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(5)
        val testClassStart = clock.instant()
        resourceState.recordTestClassStart("com.example.TestClass1", testClassStart)
        clock.advanceMillis(2)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            leakedTimestamp
        )
        clock.advanceMillis(3)
        val testClassEnd = clock.instant()
        resourceState.recordTestClassEnd("com.example.TestClass1", testClassEnd)

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("test.property.leaked") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("com.example.TestClass1") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(testClassStart.toString()) })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(testClassEnd.toString()) })
    }

    @Test
    fun `report does not attribute leaks to test classes outside time range`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(5)
        val testClassStart = clock.instant()
        resourceState.recordTestClassStart("com.example.TestClass1", testClassStart)
        clock.advanceMillis(2)
        val testClassEnd = clock.instant()
        resourceState.recordTestClassEnd("com.example.TestClass1", testClassEnd)
        clock.advanceMillis(3)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("test.property.leaked") })
        val messages = logAssertionExtension.messagesMatchingPredicate(LogAssertingExtension.Level.INFO) { it.contains("test.property.leaked") }
        assertFalse(messages.any { it.contains("com.example.TestClass1") })
    }

    @Test
    fun `build failure not triggered when build failure resource types not configured`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.clearProperty("resource.leak.detector.build.failure.resource.types")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    @Test
    fun `build failure not triggered when no leaks exist for configured resource type`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "systemprops")
        resourceState.recordBaselineResources(emptySet(), clock.instant())

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    @Test
    fun `build failure triggered when leaks detected for configured resource type`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "systemprops")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertTrue(buildFailureTriggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("systemprops") })
    }

    @Test
    fun `build failure not triggered when leaks exist but resource type not in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "ports")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.leaked")),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    @Test
    fun `build failure not triggered for baseline-only resources`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "systemprops")
        val baselineProp = ResourceId.PropertyId("baseline.property")
        resourceState.recordBaselineResources(setOf(baselineProp), clock.instant())

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    @Test
    fun `report does not report env var leaks when not monitored`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.EnvironmentVariableId("LEAKED_VAR")),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Environment Variable Leaks") })
    }

    @Test
    fun `report detects and reports leaked environment variables`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "envvars")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(10)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.EnvironmentVariableId("LEAKED_VAR")),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Environment Variable Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("LEAKED_VAR") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(leakedTimestamp.toString()) })
    }

    @Test
    fun `report excludes baseline env vars from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "envvars")
        val timestamp = clock.instant()
        val baselineEnvVar = ResourceId.EnvironmentVariableId("BASELINE_VAR")
        resourceState.recordBaselineResources(setOf(baselineEnvVar), timestamp)
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(baselineEnvVar, ResourceId.EnvironmentVariableId("LEAKED_VAR")),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Environment Variable Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("LEAKED_VAR") })
        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("BASELINE_VAR") && it.contains("Variable:") })
    }

    @Test
    fun `report excludes destroyed env vars from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "envvars")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.EnvironmentVariableId("TEMP_VAR")),
            clock.instant()
        )
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(emptySet(), clock.instant())

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("TEMP_VAR") && it.contains("Variable:") })
    }

    @Test
    fun `report attributes env var leaks to candidate test classes`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "envvars")
        val timestamp = clock.instant()
        resourceState.recordBaselineResources(emptySet(), timestamp)
        clock.advanceMillis(5)
        val testClassStart = clock.instant()
        resourceState.recordTestClassStart("com.example.EnvVarLeakingTest", testClassStart)
        clock.advanceMillis(2)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.EnvironmentVariableId("LEAKED_VAR")),
            leakedTimestamp
        )
        clock.advanceMillis(3)
        val testClassEnd = clock.instant()
        resourceState.recordTestClassEnd("com.example.EnvVarLeakingTest", testClassEnd)

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("LEAKED_VAR") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("com.example.EnvVarLeakingTest") })
    }

    @Test
    fun `build failure triggered when env var leaks detected and envvars in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "envvars")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "envvars")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.EnvironmentVariableId("LEAKED_VAR")),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertTrue(buildFailureTriggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("envvars") })
    }

    @Test
    fun `build failure not triggered when env var leaks exist but envvars not in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "envvars")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "systemprops")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.EnvironmentVariableId("LEAKED_VAR")),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    @Test
    fun `report does not report memory leaks when not monitored`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(100 * 1_048_576L, clock.instant()))
        clock.advanceMillis(10)
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(2000 * 1_048_576L, clock.instant()))

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Memory Leaks") })
    }

    @Test
    fun `report detects and reports memory leak when growth exceeds threshold`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "100")
        val baselineBytes = 256L * 1_048_576L
        val finalBytes = 512L * 1_048_576L
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(baselineBytes, clock.instant()))
        clock.advanceMillis(10)
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(finalBytes, clock.instant()))

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Memory Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Baseline: 256 MB") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Final: 512 MB") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Increase: 256 MB") })
    }

    @Test
    fun `report does not report memory leak when growth within threshold`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "500")
        val baselineBytes = 256L * 1_048_576L
        val finalBytes = 356L * 1_048_576L
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(baselineBytes, clock.instant()))
        clock.advanceMillis(10)
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(finalBytes, clock.instant()))

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Memory Leaks") })
    }

    @Test
    fun `report attributes memory leak to candidate test classes`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "100")
        val baselineBytes = 256L * 1_048_576L

        resourceState.recordNumericMeasurement(NumericResourceMeasurement(baselineBytes, clock.instant()))
        clock.advanceMillis(5)
        val testClassStart = clock.instant()
        resourceState.recordTestClassStart("com.example.MemoryLeakingTest", testClassStart)
        clock.advanceMillis(2)
        val growthTimestamp = clock.instant()
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(512L * 1_048_576L, growthTimestamp))
        clock.advanceMillis(3)
        val testClassEnd = clock.instant()
        resourceState.recordTestClassEnd("com.example.MemoryLeakingTest", testClassEnd)
        clock.advanceMillis(2)
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(512L * 1_048_576L, clock.instant()))

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Memory Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("com.example.MemoryLeakingTest") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("First Detected: $growthTimestamp") })
    }

    @Test
    fun `build failure triggered when memory leaks detected and memory in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "100")
        val baselineBytes = 256L * 1_048_576L
        val finalBytes = 512L * 1_048_576L
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(baselineBytes, clock.instant()))
        clock.advanceMillis(10)
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(finalBytes, clock.instant()))

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertTrue(buildFailureTriggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("memory") })
    }

    @Test
    fun `build failure not triggered when memory growth within threshold`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "memory")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "memory")
        System.setProperty("resource.leak.detector.memory.growth.threshold.mb", "500")
        val baselineBytes = 256L * 1_048_576L
        val finalBytes = 356L * 1_048_576L
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(baselineBytes, clock.instant()))
        clock.advanceMillis(10)
        resourceState.recordNumericMeasurement(NumericResourceMeasurement(finalBytes, clock.instant()))

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    @Test
    fun `build failure not triggered for destroyed resources`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "systemprops")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "systemprops")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PropertyId("test.property.temporary")),
            clock.instant()
        )
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(emptySet(), clock.instant())

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertFalse(buildFailureTriggered)
    }

    // --- Thread leak reporter tests ---

    @Test
    fun `report does not report thread leaks when not monitored`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId("leaked-thread", 999)),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread Leaks") })
    }

    @Test
    fun `report detects and reports leaked threads`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "threads")
        System.setProperty("resource.leak.detector.thread.grace.period.seconds", "0")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        // Use the main thread which we know is alive during the grace period re-check
        val mainThread = Thread.currentThread()
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId(mainThread.name, mainThread.id)),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread: ${mainThread.name} (ID: ${mainThread.id})") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(leakedTimestamp.toString()) })
    }

    @Test
    fun `report excludes baseline threads from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "threads")
        System.setProperty("resource.leak.detector.thread.grace.period.seconds", "0")
        val mainThread = Thread.currentThread()
        val baselineThread = ResourceId.ThreadId(mainThread.name, mainThread.id)
        resourceState.recordBaselineResources(setOf(baselineThread), clock.instant())

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread Leaks") })
    }

    @Test
    fun `report excludes destroyed threads from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "threads")
        System.setProperty("resource.leak.detector.thread.grace.period.seconds", "0")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId("temp-thread", 888)),
            clock.instant()
        )
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(emptySet(), clock.instant())

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("temp-thread") && it.contains("Thread:") })
    }

    @Test
    fun `report attributes thread leaks to candidate test classes`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "threads")
        System.setProperty("resource.leak.detector.thread.grace.period.seconds", "0")
        val mainThread = Thread.currentThread()
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(5)
        val testClassStart = clock.instant()
        resourceState.recordTestClassStart("com.example.ThreadLeakingTest", testClassStart)
        clock.advanceMillis(2)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId(mainThread.name, mainThread.id)),
            leakedTimestamp
        )
        clock.advanceMillis(3)
        val testClassEnd = clock.instant()
        resourceState.recordTestClassEnd("com.example.ThreadLeakingTest", testClassEnd)

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread: ${mainThread.name}") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("com.example.ThreadLeakingTest") })
    }

    @Test
    fun `build failure triggered when thread leaks detected and threads in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "threads")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "threads")
        System.setProperty("resource.leak.detector.thread.grace.period.seconds", "0")
        val mainThread = Thread.currentThread()
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId(mainThread.name, mainThread.id)),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertTrue(buildFailureTriggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("threads") })
    }

    @Test
    fun `thread grace period filters out terminated threads`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "threads")
        System.setProperty("resource.leak.detector.thread.grace.period.seconds", "0")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        // Use a thread ID that won't exist after grace period re-check
        resourceState.updateDiscreteResources(
            setOf(ResourceId.ThreadId("already-terminated-thread", -99999)),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        // Thread should be filtered out during grace period re-check since it no longer exists
        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Thread Leaks") })
    }

    // --- Port leak reporter tests ---

    @Test
    fun `report does not report port leaks when not monitored`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PortId(8080)),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port Leaks") })
    }

    @Test
    fun `report detects and reports leaked ports`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ports")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PortId(8080)),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port: 8080") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(leakedTimestamp.toString()) })
    }

    @Test
    fun `report excludes baseline ports from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ports")
        val baselinePort = ResourceId.PortId(8080)
        resourceState.recordBaselineResources(setOf(baselinePort), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(baselinePort, ResourceId.PortId(9090)),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port: 9090") })
        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Port: 8080") && it.contains("Port:") })
    }

    @Test
    fun `build failure triggered when port leaks detected and ports in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ports")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "ports")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.PortId(8080)),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertTrue(buildFailureTriggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("ports") })
    }

    // --- DynamoDB table leak reporter tests ---

    @Test
    fun `report does not report DynamoDB table leaks when not monitored`() {
        System.clearProperty("resource.leak.detector.monitored.resource.types")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.DynamoDbTableId("leaked-table")),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("DynamoDB Table Leaks") })
    }

    @Test
    fun `report detects and reports leaked DynamoDB tables`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ddbtables")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        val leakedTimestamp = clock.instant()
        resourceState.updateDiscreteResources(
            setOf(ResourceId.DynamoDbTableId("leaked-table")),
            leakedTimestamp
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("DynamoDB Table Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Table: leaked-table") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains(leakedTimestamp.toString()) })
    }

    @Test
    fun `report excludes baseline DynamoDB tables from leaks`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ddbtables")
        val baselineTable = ResourceId.DynamoDbTableId("baseline-table")
        resourceState.recordBaselineResources(setOf(baselineTable), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(baselineTable, ResourceId.DynamoDbTableId("leaked-table")),
            clock.instant()
        )

        ResourceLeakReporter(resourceState).report()

        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("DynamoDB Table Leaks") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Table: leaked-table") })
        assertFalse(logAssertionExtension.logged(LogAssertingExtension.Level.INFO) { it.contains("Table: baseline-table") })
    }

    @Test
    fun `build failure triggered when DynamoDB table leaks detected and ddbtables in build failure list`() {
        System.setProperty("resource.leak.detector.monitored.resource.types", "ddbtables")
        System.setProperty("resource.leak.detector.build.failure.resource.types", "ddbtables")
        resourceState.recordBaselineResources(emptySet(), clock.instant())
        clock.advanceMillis(10)
        resourceState.updateDiscreteResources(
            setOf(ResourceId.DynamoDbTableId("leaked-table")),
            clock.instant()
        )

        var buildFailureTriggered = false
        ResourceLeakReporter(resourceState, buildFailureAction = { buildFailureTriggered = true }).report()

        assertTrue(buildFailureTriggered)
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("Build failure triggered") })
        assertTrue(logAssertionExtension.logged(LogAssertingExtension.Level.ERROR) { it.contains("ddbtables") })
    }
}
