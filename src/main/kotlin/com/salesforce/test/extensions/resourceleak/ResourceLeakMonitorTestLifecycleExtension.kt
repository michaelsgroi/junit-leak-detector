package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.time.Clock

class ResourceLeakMonitorTestLifecycleExtension(
    // Default parameters allow JUnit's service loader to instantiate via empty constructor
    // while still enabling dependency injection for testing
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC(),
    private val configuration: Configuration = Configuration.instance,
    private val registryProvider: () -> MonitorRegistry? = { SharedMonitorRegistry.get() }
) : BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    override fun beforeAll(extensionContext: ExtensionContext) {
        val testClassName = extensionContext.requiredTestClass.name
        resourceState.recordTestClassStart(testClassName, clock.instant())
        registryProvider()?.snapshotAll(kind = SnapshotKind.BEFORE_ALL, testClass = testClassName)
    }

    override fun afterAll(extensionContext: ExtensionContext) {
        val testClassName = extensionContext.requiredTestClass.name
        registryProvider()?.snapshotAll(kind = SnapshotKind.AFTER_ALL, testClass = testClassName)
        resourceState.recordTestClassEnd(testClassName, clock.instant())
    }

    override fun beforeEach(extensionContext: ExtensionContext) {
        if (configuration.snapshotGranularity != SnapshotGranularity.TEST) return
        val key = methodKey(extensionContext) ?: return
        resourceState.recordTestMethodStart(key, clock.instant())
        registryProvider()?.snapshotAll(
            kind = SnapshotKind.BEFORE_EACH,
            testClass = key.testClassName,
            testMethod = key.testMethodName
        )
    }

    override fun afterEach(extensionContext: ExtensionContext) {
        if (configuration.snapshotGranularity != SnapshotGranularity.TEST) return
        val key = methodKey(extensionContext) ?: return
        registryProvider()?.snapshotAll(
            kind = SnapshotKind.AFTER_EACH,
            testClass = key.testClassName,
            testMethod = key.testMethodName
        )
        resourceState.recordTestMethodEnd(key, clock.instant())
    }

    private fun methodKey(context: ExtensionContext): TestMethodKey? {
        val className = context.testClass.orElse(null)?.name ?: return null
        val methodName = context.testMethod.orElse(null)?.name ?: return null
        return TestMethodKey(className, methodName)
    }
}
