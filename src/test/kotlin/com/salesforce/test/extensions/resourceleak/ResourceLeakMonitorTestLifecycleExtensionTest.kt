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
import java.util.function.Function

class ResourceLeakMonitorTestLifecycleExtensionTest {
    private val testResourceState = ResourceState()
    private val clock = TestClock(0L) // Clock starts at 0ms
    private val testee = ResourceLeakMonitorTestLifecycleExtension(
        resourceState = testResourceState,
        clock = clock,
        registryProvider = { null } // overridden per-test where needed
    )
    private val testClass1 = TestClass1::class.java
    private val testClass2 = TestClass2::class.java

    @Test
    fun `extension records test class start and end timestamps`() {
        val mockContext1 = createMockExtensionContext(testClass1)
        testee.beforeAll(mockContext1)
        clock.advanceMillis(1L) // Clock is now 1ms
        testee.afterAll(mockContext1)

        clock.advanceMillis(1L) // Clock is now 2ms

        val mockContext2 = createMockExtensionContext(testClass2)
        testee.beforeAll(mockContext2)
        clock.advanceMillis(1L) // Clock is now 3ms
        testee.afterAll(mockContext2)

        assertEquals(
            mapOf(
                testClass1.name to TestClassLifecycle(Instant.ofEpochMilli(0L), Instant.ofEpochMilli(1L)),
                testClass2.name to TestClassLifecycle(Instant.ofEpochMilli(2L), Instant.ofEpochMilli(3L))
            ),
            testResourceState.getAllTestClassLifecycles()
        )
    }

    @Test
    fun `each callback triggers a snapshot via the registry`() {
        val state = ResourceState()
        var calls = 0
        val reg = MonitorRegistry(
            resourceState = state,
            clock = clock,
            classPresent = { true },
            configuration = configWithMonitored("systemprops")
        )
        // Wrap reg with a counting decorator using a registry provider that increments and delegates
        val extension = ResourceLeakMonitorTestLifecycleExtension(
            resourceState = state,
            clock = clock,
            registryProvider = {
                calls++
                reg
            }
        )
        val ctx = createMockExtensionContext(testClass1)
        extension.beforeAll(ctx)
        extension.beforeEach(ctx)
        extension.afterEach(ctx)
        extension.afterAll(ctx)
        assertEquals(4, calls)
    }

    @Test
    fun `callbacks are no-ops when no registry available`() {
        val ctx = createMockExtensionContext(testClass1)
        // registryProvider returns null on the testee
        testee.beforeAll(ctx)
        testee.beforeEach(ctx)
        testee.afterEach(ctx)
        testee.afterAll(ctx)
        // No exception thrown, lifecycle still recorded
        assertEquals(1, testResourceState.getAllTestClassLifecycles().size)
    }

    private fun configWithMonitored(types: String): Configuration {
        val props = java.util.Properties().apply { setProperty("monitored.resource.types", types) }
        return Configuration(propertiesLoader = { props }, systemPropertyLookup = { null })
    }

    private fun createMockExtensionContext(testClass: Class<*>): ExtensionContext {
        return object : ExtensionContext {
            override fun getRequiredTestClass(): Class<*> = testClass
            override fun getTestClass(): Optional<Class<*>> = Optional.of(testClass)
            override fun getUniqueId(): String = "test"
            override fun getDisplayName(): String = testClass.name
            override fun getTags(): MutableSet<String> = mutableSetOf()
            override fun getParent(): Optional<ExtensionContext> = Optional.empty()
            override fun getRoot(): ExtensionContext = this
            override fun getStore(namespace: ExtensionContext.Namespace): Store {
                throw UnsupportedOperationException()
            }
            override fun publishReportEntry(entries: MutableMap<String, String>) {
                throw UnsupportedOperationException()
            }
            override fun getConfigurationParameter(key: String): Optional<String> = Optional.empty()
            override fun <T> getConfigurationParameter(key: String, transformer: Function<String, T>): Optional<T> {
                throw UnsupportedOperationException()
            }
            override fun getTestMethod(): Optional<Method> = Optional.empty()
            override fun getTestInstance(): Optional<Any> = Optional.empty()
            override fun getTestInstances(): Optional<TestInstances> = Optional.empty()
            override fun getTestInstanceLifecycle(): Optional<Lifecycle> = Optional.empty()
            override fun getExecutionMode(): ExecutionMode = ExecutionMode.SAME_THREAD
            override fun getElement(): Optional<AnnotatedElement> = Optional.empty()
            override fun getExecutableInvoker(): ExecutableInvoker {
                throw UnsupportedOperationException()
            }
            override fun getExecutionException(): Optional<Throwable> = Optional.empty()
        }
    }

    private class TestClass1
    private class TestClass2
}
