package com.michaelsgroi.test.extensions.resourceleak

import com.michaelsgroi.test.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestIdentifier
import java.util.Properties

/**
 * Verifies the [ResourceLeakMonitor]'s mapping from JUnit Platform `TestIdentifier`
 * events to per-class / per-method lifecycle records, mirroring the test kinds validated
 * in `docs/listener-migration-spike.md`:
 *  - plain `@Test`
 *  - `@ParameterizedTest`
 *  - `@TestFactory`
 *  - `@Nested`
 *  - `@RepeatedTest`
 *  - PER_CLASS instance lifecycle
 *
 * For each kind, the test feeds the listener the same `TestIdentifier` events JUnit
 * actually emits and asserts the resulting `ResourceState` lifecycles match what the
 * old Jupiter-Extension code recorded. No real `MonitorRegistry` is needed: with no
 * monitors enabled (empty `monitored.resource.types`) the listener still records
 * lifecycles via `ResourceState`, which is what attribution consumes.
 */
class ResourceLeakMonitorListenerEventsTest {
    private val clock = TestClock(0L)

    @Test
    fun `plain @Test - class lifecycle recorded once, per-method lifecycle recorded under TEST granularity`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "test")

        val classId = container(uid("class:com.A"), "A", ClassSource.from("com.A"))
        val methodId =
            test(uid("class:com.A/method:a()"), "a()", MethodSource.from("com.A", "a"))

        monitor.executionStarted(classId)
        clock.advanceMillis(1)
        monitor.executionStarted(methodId)
        clock.advanceMillis(1)
        monitor.executionFinished(methodId, success())
        clock.advanceMillis(1)
        monitor.executionFinished(classId, success())

        assertNotNull(state.getAllTestClassLifecycles()["com.A"])
        assertNotNull(state.getAllTestMethodLifecycles()[TestMethodKey("com.A", "a")])
    }

    @Test
    fun `class granularity - per-method events do not record per-method lifecycles`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "class")

        val classId = container(uid("class:com.A"), "A", ClassSource.from("com.A"))
        val methodId = test(uid("class:com.A/method:a()"), "a()", MethodSource.from("com.A", "a"))

        monitor.executionStarted(classId)
        monitor.executionStarted(methodId)
        monitor.executionFinished(methodId, success())
        monitor.executionFinished(classId, success())

        assertNotNull(state.getAllTestClassLifecycles()["com.A"])
        assertTrue(state.getAllTestMethodLifecycles().isEmpty())
    }

    @Test
    fun `parameterized test - template Container is ignored, invocations record per-method lifecycle`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "test")

        val classId = container(uid("class:com.B"), "B", ClassSource.from("com.B"))
        // Template: Container + MethodSource. Must NOT be treated as either class or method.
        val templateId =
            container(uid("class:com.B/test-template:p(int)"), "p(int)", MethodSource.from("com.B", "p", "int"))
        val inv1 =
            test(uid("class:com.B/test-template:p(int)/test-template-invocation:#1"), "[1] 1", MethodSource.from("com.B", "p", "int"))
        val inv2 =
            test(uid("class:com.B/test-template:p(int)/test-template-invocation:#2"), "[2] 2", MethodSource.from("com.B", "p", "int"))

        monitor.executionStarted(classId)
        monitor.executionStarted(templateId)
        monitor.executionStarted(inv1)
        clock.advanceMillis(1)
        monitor.executionFinished(inv1, success())
        monitor.executionStarted(inv2)
        clock.advanceMillis(1)
        monitor.executionFinished(inv2, success())
        monitor.executionFinished(templateId, success())
        monitor.executionFinished(classId, success())

        assertNotNull(state.getAllTestClassLifecycles()["com.B"])
        // All invocations resolve to the same (class, method) key.
        assertEquals(setOf(TestMethodKey("com.B", "p")), state.getAllTestMethodLifecycles().keys)
    }

    @Test
    fun `factory dynamic tests - factory Container is ignored, dynamics record per-method lifecycle`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "test")

        val classId = container(uid("class:com.C"), "C", ClassSource.from("com.C"))
        val factoryId =
            container(uid("class:com.C/test-factory:dyn()"), "dyn()", MethodSource.from("com.C", "dyn"))
        val d1 =
            test(uid("class:com.C/test-factory:dyn()/dynamic-test:#1"), "d1", MethodSource.from("com.C", "dyn"))

        monitor.executionStarted(classId)
        monitor.executionStarted(factoryId)
        monitor.executionStarted(d1)
        monitor.executionFinished(d1, success())
        monitor.executionFinished(factoryId, success())
        monitor.executionFinished(classId, success())

        assertNotNull(state.getAllTestClassLifecycles()["com.C"])
        assertEquals(setOf(TestMethodKey("com.C", "dyn")), state.getAllTestMethodLifecycles().keys)
    }

    @Test
    fun `nested test class - outer and inner each get their own class lifecycle`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "class")

        val outerId = container(uid("class:com.D"), "D", ClassSource.from("com.D"))
        val innerId =
            container(uid("class:com.D/nested-class:Inner"), "Inner", ClassSource.from("com.D\$Inner"))
        val innerMethodId =
            test(
                uid("class:com.D/nested-class:Inner/method:n()"),
                "n()",
                MethodSource.from("com.D\$Inner", "n"),
            )

        monitor.executionStarted(outerId)
        monitor.executionStarted(innerId)
        monitor.executionStarted(innerMethodId)
        monitor.executionFinished(innerMethodId, success())
        monitor.executionFinished(innerId, success())
        monitor.executionFinished(outerId, success())

        val lifecycles = state.getAllTestClassLifecycles()
        assertNotNull(lifecycles["com.D"])
        assertNotNull(lifecycles["com.D\$Inner"])
    }

    @Test
    fun `repeated test - same shape as parameterized`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "test")

        val classId = container(uid("class:com.E"), "E", ClassSource.from("com.E"))
        val templateId =
            container(uid("class:com.E/test-template:r()"), "r()", MethodSource.from("com.E", "r"))
        val rep1 =
            test(uid("class:com.E/test-template:r()/test-template-invocation:#1"), "1 of 3", MethodSource.from("com.E", "r"))

        monitor.executionStarted(classId)
        monitor.executionStarted(templateId)
        monitor.executionStarted(rep1)
        monitor.executionFinished(rep1, success())
        monitor.executionFinished(templateId, success())
        monitor.executionFinished(classId, success())

        assertNotNull(state.getAllTestClassLifecycles()["com.E"])
        assertEquals(setOf(TestMethodKey("com.E", "r")), state.getAllTestMethodLifecycles().keys)
    }

    @Test
    fun `engine descriptor (no source) is ignored as neither class nor method boundary`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "test")

        val engineId =
            TestIdentifier.from(
                FixtureDescriptor(uid("engine"), "JUnit Jupiter", source = null, kind = DescriptorKind.CONTAINER),
            )

        monitor.executionStarted(engineId)
        monitor.executionFinished(engineId, success())

        assertTrue(state.getAllTestClassLifecycles().isEmpty())
        assertTrue(state.getAllTestMethodLifecycles().isEmpty())
    }

    @Test
    fun `executionSkipped does not record any lifecycle`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "class")

        val classId = container(uid("class:com.F"), "F", ClassSource.from("com.F"))
        monitor.executionSkipped(classId, "disabled")

        assertTrue(state.getAllTestClassLifecycles().isEmpty())
    }

    @Test
    fun `class-boundary timestamps come from the clock at executionStarted and executionFinished`() {
        val state = ResourceState()
        val monitor = listener(state, granularity = "class")

        clock.setMillis(100L)
        val classId = container(uid("class:com.G"), "G", ClassSource.from("com.G"))
        monitor.executionStarted(classId)
        clock.setMillis(250L)
        monitor.executionFinished(classId, success())

        val lc = state.getAllTestClassLifecycles()["com.G"]
        assertNotNull(lc)
        assertEquals(100L, lc!!.start.toEpochMilli())
        assertEquals(250L, lc.end.toEpochMilli())
    }

    @Test
    fun `class with no source attribute is ignored (paranoid path)`() {
        // Defensive: even if some custom engine emitted a Container without ClassSource,
        // we shouldn't blow up — we just skip it.
        val state = ResourceState()
        val monitor = listener(state, granularity = "class")

        val mystery =
            TestIdentifier.from(
                FixtureDescriptor(
                    uid("class:com.X"),
                    "X",
                    // ClassSource omitted; not a kind we recognize.
                    source = null,
                    kind = DescriptorKind.CONTAINER,
                ),
            )
        monitor.executionStarted(mystery)
        monitor.executionFinished(mystery, success())

        assertTrue(state.getAllTestClassLifecycles().isEmpty())
        assertNull(state.getAllTestClassLifecycles()["com.X"])
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Builds a [ResourceLeakMonitor] with no monitors enabled and a [TestClock]. With
     *  the registry uninitialized (no `testPlanExecutionStarted` called), all
     *  `registry?.…` snapshot calls are no-ops; we observe behavior via [ResourceState]
     *  lifecycle entries, which is the data the attribution component actually
     *  consumes. */
    private fun listener(
        state: ResourceState,
        granularity: String,
    ): ResourceLeakMonitor {
        val props = Properties().apply { setProperty("snapshot.granularity", granularity) }
        val configuration = Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
        return ResourceLeakMonitor(
            resourceState = state,
            clock = clock,
            configuration = configuration,
        )
    }

    private fun container(
        id: UniqueId,
        display: String,
        source: TestSource?,
    ): TestIdentifier = TestIdentifier.from(FixtureDescriptor(id, display, source, DescriptorKind.CONTAINER))

    private fun test(
        id: UniqueId,
        display: String,
        source: TestSource?,
    ): TestIdentifier = TestIdentifier.from(FixtureDescriptor(id, display, source, DescriptorKind.TEST))

    private fun uid(segment: String): UniqueId {
        val parts = segment.split("/")
        var u = UniqueId.parse("[engine:junit-jupiter]")
        if (parts.first() == "engine") return u
        for (p in parts) {
            val (type, value) = p.split(":", limit = 2)
            u = u.append(type, value)
        }
        return u
    }

    private fun success(): TestExecutionResult = TestExecutionResult.successful()

    private enum class DescriptorKind { CONTAINER, TEST }

    private class FixtureDescriptor(
        uniqueId: UniqueId,
        displayName: String,
        source: TestSource?,
        private val kind: DescriptorKind,
    ) : AbstractTestDescriptor(uniqueId, displayName, source) {
        override fun getType(): TestDescriptor.Type =
            when (kind) {
                DescriptorKind.CONTAINER -> TestDescriptor.Type.CONTAINER
                DescriptorKind.TEST -> TestDescriptor.Type.TEST
            }
    }
}
