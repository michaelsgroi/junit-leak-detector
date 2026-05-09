package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.time.Clock

class ResourceLeakMonitorTestLifecycleExtension(
    // Default parameter allows JUnit's service loader to instantiate via empty constructor
    // while still enabling dependency injection for testing
    private val resourceState: ResourceState = ResourceState.instance,
    private val clock: Clock = Clock.systemUTC()
) : BeforeAllCallback, AfterAllCallback {
    override fun beforeAll(extensionContext: ExtensionContext) =
        resourceState.recordTestClassStart(
            testClassName = extensionContext.requiredTestClass.name,
            startTimestamp = clock.instant()
        )

    override fun afterAll(extensionContext: ExtensionContext) =
        resourceState.recordTestClassEnd(
            testClassName = extensionContext.requiredTestClass.name,
            endTimestamp = clock.instant()
        )
}
