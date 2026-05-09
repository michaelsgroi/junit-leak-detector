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
    private val registryProvider: () -> MonitorRegistry? = { SharedMonitorRegistry.get() },
    private val settleWaiter: PreclassSettleWaiter = PreclassSettleWaiter(configuration),
) : BeforeAllCallback,
    AfterAllCallback,
    BeforeEachCallback,
    AfterEachCallback {
    // State for the pre-class settle wait (per JUnit Jupiter, a single Extension instance is
    // reused for the entire test plan, so this state is naturally scoped to one suite run).
    private var previousClassDelta: Map<ResourceType, Set<ResourceId>>? = null
    private var currentClassBeforeAllProbe: Map<ResourceType, Set<ResourceId>>? = null

    override fun beforeAll(extensionContext: ExtensionContext) {
        val testClassName = extensionContext.requiredTestClass.name
        val registry = registryProvider()
        if (configuration.preclassSettleEnabled && registry != null) {
            previousClassDelta?.let { delta ->
                settleWaiter.waitForSettle(delta) { types -> registry.probeDiscrete(types) }
            }
        }
        resourceState.recordTestClassStart(testClassName, clock.instant())
        if (configuration.preclassSettleEnabled && registry != null) {
            currentClassBeforeAllProbe = registry.probeDiscrete(SETTLE_TYPES)
        }
        registry?.snapshotAll(kind = SnapshotKind.BEFORE_ALL, testClass = testClassName)
    }

    override fun afterAll(extensionContext: ExtensionContext) {
        val testClassName = extensionContext.requiredTestClass.name
        val registry = registryProvider()
        registry?.snapshotAll(kind = SnapshotKind.AFTER_ALL, testClass = testClassName)
        resourceState.recordTestClassEnd(testClassName, clock.instant())
        if (configuration.preclassSettleEnabled && registry != null) {
            val before = currentClassBeforeAllProbe ?: emptyMap()
            val after = registry.probeDiscrete(SETTLE_TYPES)
            previousClassDelta = computeDelta(before, after)
            currentClassBeforeAllProbe = null
        }
    }

    override fun beforeEach(extensionContext: ExtensionContext) {
        if (configuration.snapshotGranularity != SnapshotGranularity.TEST) return
        val key = methodKey(extensionContext) ?: return
        resourceState.recordTestMethodStart(key, clock.instant())
        registryProvider()?.snapshotAll(
            kind = SnapshotKind.BEFORE_EACH,
            testClass = key.testClassName,
            testMethod = key.testMethodName,
        )
    }

    override fun afterEach(extensionContext: ExtensionContext) {
        if (configuration.snapshotGranularity != SnapshotGranularity.TEST) return
        val key = methodKey(extensionContext) ?: return
        registryProvider()?.snapshotAll(
            kind = SnapshotKind.AFTER_EACH,
            testClass = key.testClassName,
            testMethod = key.testMethodName,
        )
        resourceState.recordTestMethodEnd(key, clock.instant())
    }

    private fun methodKey(context: ExtensionContext): TestMethodKey? {
        val className = context.testClass.orElse(null)?.name ?: return null
        val methodName = context.testMethod.orElse(null)?.name ?: return null
        return TestMethodKey(className, methodName)
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
    }
}
