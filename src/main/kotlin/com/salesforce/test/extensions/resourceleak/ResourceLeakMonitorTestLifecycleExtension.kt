package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.time.Clock

class ResourceLeakMonitorTestLifecycleExtension(
    // Default parameter allows JUnit's service loader to instantiate via empty constructor
    // while still enabling dependency injection for testing
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC(),
    private val registryProvider: () -> MonitorRegistry? = { SharedMonitorRegistry.get() }
) : BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    override fun beforeAll(extensionContext: ExtensionContext) {
        resourceState.recordTestClassStart(
            testClassName = extensionContext.requiredTestClass.name,
            startTimestamp = clock.instant()
        )
        registryProvider()?.snapshotAll()
    }

    override fun afterAll(extensionContext: ExtensionContext) {
        registryProvider()?.snapshotAll()
        resourceState.recordTestClassEnd(
            testClassName = extensionContext.requiredTestClass.name,
            endTimestamp = clock.instant()
        )
    }

    override fun beforeEach(extensionContext: ExtensionContext) {
        registryProvider()?.snapshotAll()
    }

    override fun afterEach(extensionContext: ExtensionContext) {
        registryProvider()?.snapshotAll()
    }
}
