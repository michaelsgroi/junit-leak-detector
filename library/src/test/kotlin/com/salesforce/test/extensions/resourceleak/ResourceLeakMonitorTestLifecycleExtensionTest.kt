package com.salesforce.test.extensions.resourceleak

import com.salesforce.test.TestClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.extension.ExecutableInvoker
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store
import org.junit.jupiter.api.extension.TestInstances
import org.junit.jupiter.api.parallel.ExecutionMode
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.time.Instant
import java.util.Optional
import java.util.Properties
import java.util.function.Function

class ResourceLeakMonitorTestLifecycleExtensionTest {
    private val testResourceState = ResourceState()
    private val clock = TestClock(0L)
    private val testClass1 = TestClass1::class.java
    private val testClass2 = TestClass2::class.java

    private fun extensionWith(
        configProps: Map<String, String> = emptyMap(),
        registryProvider: () -> MonitorRegistry? = { null },
        state: ResourceState = testResourceState,
    ): ResourceLeakMonitorTestLifecycleExtension {
        val props = Properties().apply { configProps.forEach { (k, v) -> setProperty(k, v) } }
        val configuration = Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
        return ResourceLeakMonitorTestLifecycleExtension(
            resourceState = state,
            clock = clock,
            configuration = configuration,
            registryProvider = registryProvider,
        )
    }

    @Test
    fun `extension records test class start and end timestamps`() {
        val testee = extensionWith()
        val mockContext1 = createMockExtensionContext(testClass1)
        testee.beforeAll(mockContext1)
        clock.advanceMillis(1L)
        testee.afterAll(mockContext1)

        clock.advanceMillis(1L)

        val mockContext2 = createMockExtensionContext(testClass2)
        testee.beforeAll(mockContext2)
        clock.advanceMillis(1L)
        testee.afterAll(mockContext2)

        assertEquals(
            mapOf(
                testClass1.name to TestClassLifecycle(Instant.ofEpochMilli(0L), Instant.ofEpochMilli(1L)),
                testClass2.name to TestClassLifecycle(Instant.ofEpochMilli(2L), Instant.ofEpochMilli(3L)),
            ),
            testResourceState.getAllTestClassLifecycles(),
        )
    }

    @Test
    fun `class granularity by default makes per-each callbacks no-op`() {
        var calls = 0
        val testee =
            extensionWith(registryProvider = {
                calls++
                null
            })
        val ctx = createMockExtensionContext(testClass1, methodName = "m")

        testee.beforeAll(ctx)
        testee.beforeEach(ctx)
        testee.afterEach(ctx)
        testee.afterAll(ctx)

        assertEquals(2, calls, "only beforeAll/afterAll should query the registry under class granularity")
        assertEquals(emptyMap<TestMethodKey, TestClassLifecycle>(), testResourceState.getAllTestMethodLifecycles())
    }

    @Test
    fun `test granularity records per-test lifecycles and triggers per-each snapshots`() {
        var calls = 0
        val testee =
            extensionWith(
                configProps = mapOf("snapshot.granularity" to "test"),
                registryProvider = {
                    calls++
                    null
                },
            )
        val ctx = createMockExtensionContext(testClass1, methodName = "m1")

        testee.beforeAll(ctx)
        clock.advanceMillis(1)
        testee.beforeEach(ctx)
        clock.advanceMillis(1)
        testee.afterEach(ctx)
        clock.advanceMillis(1)
        testee.afterAll(ctx)

        assertEquals(4, calls, "all four callbacks should query the registry under test granularity")
        val key = TestMethodKey(testClass1.name, "m1")
        assertEquals(
            TestClassLifecycle(Instant.ofEpochMilli(1), Instant.ofEpochMilli(2)),
            testResourceState.getAllTestMethodLifecycles()[key],
        )
    }

    @Test
    fun `pre-class settle is not invoked when disabled`() {
        var settleCalls = 0
        val fakeWaiter =
            object : PreclassSettleWaiter(maxSeconds = 10L, pollIntervalSeconds = 1L) {
                override fun waitForSettle(
                    previousClassDelta: Map<ResourceType, Set<ResourceId>>,
                    probe: (Set<ResourceType>) -> Map<ResourceType, Set<ResourceId>>,
                ) {
                    settleCalls++
                }
            }
        val emptyRegistry = noMonitorRegistry()
        val testee = extensionWithSettle(enabled = false, registryProvider = { emptyRegistry }, waiter = fakeWaiter)

        val ctxA = createMockExtensionContext(testClass1)
        val ctxB = createMockExtensionContext(testClass2)
        testee.beforeAll(ctxA)
        testee.afterAll(ctxA)
        testee.beforeAll(ctxB)
        testee.afterAll(ctxB)

        assertEquals(0, settleCalls)
    }

    @Test
    fun `pre-class settle is invoked at second BeforeAll when enabled`() {
        var settleCalls = 0
        val fakeWaiter =
            object : PreclassSettleWaiter(maxSeconds = 10L, pollIntervalSeconds = 1L) {
                override fun waitForSettle(
                    previousClassDelta: Map<ResourceType, Set<ResourceId>>,
                    probe: (Set<ResourceType>) -> Map<ResourceType, Set<ResourceId>>,
                ) {
                    settleCalls++
                }
            }
        val emptyRegistry = noMonitorRegistry()
        val testee = extensionWithSettle(enabled = true, registryProvider = { emptyRegistry }, waiter = fakeWaiter)

        val ctxA = createMockExtensionContext(testClass1)
        val ctxB = createMockExtensionContext(testClass2)
        testee.beforeAll(ctxA) // first class — no previous delta yet, no wait
        testee.afterAll(ctxA)
        testee.beforeAll(ctxB) // second class — wait fires
        testee.afterAll(ctxB)

        assertEquals(1, settleCalls)
    }

    private fun noMonitorRegistry(): MonitorRegistry =
        MonitorRegistry(
            resourceState = ResourceState(),
            clock = clock,
            classPresent = { true },
            configuration = Configuration(propertiesLoader = { Properties() }, systemPropertyLookup = { null }),
            rawReportWriter = null,
        )

    private fun extensionWithSettle(
        enabled: Boolean,
        registryProvider: () -> MonitorRegistry?,
        waiter: PreclassSettleWaiter,
    ): ResourceLeakMonitorTestLifecycleExtension {
        val props = Properties().apply { setProperty("preclass.settle.enabled", enabled.toString()) }
        val configuration = Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
        return ResourceLeakMonitorTestLifecycleExtension(
            resourceState = testResourceState,
            clock = clock,
            configuration = configuration,
            registryProvider = registryProvider,
            settleWaiter = waiter,
        )
    }

    @Test
    fun `callbacks are no-ops when no registry available`() {
        val testee = extensionWith()
        val ctx = createMockExtensionContext(testClass1)
        testee.beforeAll(ctx)
        testee.beforeEach(ctx)
        testee.afterEach(ctx)
        testee.afterAll(ctx)
        assertEquals(1, testResourceState.getAllTestClassLifecycles().size)
    }

    private fun createMockExtensionContext(
        testClass: Class<*>,
        methodName: String? = null,
    ): ExtensionContext {
        val method: Method? = methodName?.let { testClass.declaredMethods.firstOrNull { m -> m.name == it } }
        return object : ExtensionContext {
            override fun getRequiredTestClass(): Class<*> = testClass

            override fun getTestClass(): Optional<Class<*>> = Optional.of(testClass)

            override fun getUniqueId(): String = "test"

            override fun getDisplayName(): String = testClass.name

            override fun getTags(): MutableSet<String> = mutableSetOf()

            override fun getParent(): Optional<ExtensionContext> = Optional.empty()

            override fun getRoot(): ExtensionContext = this

            override fun getStore(namespace: ExtensionContext.Namespace): Store = throw UnsupportedOperationException()

            override fun publishReportEntry(entries: MutableMap<String, String>): Unit = throw UnsupportedOperationException()

            override fun getConfigurationParameter(key: String): Optional<String> = Optional.empty()

            override fun <T> getConfigurationParameter(
                key: String,
                transformer: Function<String, T>,
            ): Optional<T> = throw UnsupportedOperationException()

            override fun getTestMethod(): Optional<Method> = Optional.ofNullable(method)

            override fun getTestInstance(): Optional<Any> = Optional.empty()

            override fun getTestInstances(): Optional<TestInstances> = Optional.empty()

            override fun getTestInstanceLifecycle(): Optional<Lifecycle> = Optional.empty()

            override fun getExecutionMode(): ExecutionMode = ExecutionMode.SAME_THREAD

            override fun getElement(): Optional<AnnotatedElement> = Optional.empty()

            override fun getExecutableInvoker(): ExecutableInvoker = throw UnsupportedOperationException()

            override fun getExecutionException(): Optional<Throwable> = Optional.empty()
        }
    }

    @Suppress("unused")
    private class TestClass1 {
        fun m1() {}

        fun m() {}
    }

    @Suppress("unused")
    private class TestClass2 {
        fun m() {}
    }
}
