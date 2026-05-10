package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate
import org.springframework.test.context.support.AnnotationConfigContextLoader

class SpringContextShutdownHookTest {
    @AfterEach
    fun cleanup() {
        // Drop anything we left behind so other tests don't see our cached contexts.
        SpringContextShutdownHook().shutdown()
    }

    @Configuration
    private open class FixtureConfigA

    @Configuration
    private open class FixtureConfigB

    @Test
    fun `silent no-op when Spring TestContext cache is empty`() {
        // Pre-clean to ensure empty.
        SpringContextShutdownHook().shutdown()
        assertEquals(0, currentCacheSize())

        // Calling shutdown on an already-empty cache should leave it empty and not error.
        SpringContextShutdownHook().shutdown()
        assertEquals(0, currentCacheSize())
    }

    @Test
    fun `closes all cached contexts`() {
        val ctxA = loadContext(FixtureConfigA::class.java)
        val ctxB = loadContext(FixtureConfigB::class.java)
        val sizeBefore = currentCacheSize()
        assertTrue(sizeBefore > 0, "expected at least one cached context after loadContext()")
        assertTrue(ctxA.isActive, "fixture A should be active before shutdown")
        assertTrue(ctxB.isActive, "fixture B should be active before shutdown")

        SpringContextShutdownHook().shutdown()

        assertEquals(0, currentCacheSize(), "expected cache to be empty after shutdown")
        // The real assertion: the underlying contexts are torn down, not just unmapped.
        assertTrue(!ctxA.isActive, "fixture A context should be closed after shutdown")
        assertTrue(!ctxB.isActive, "fixture B context should be closed after shutdown")
    }

    private fun loadContext(configClass: Class<*>): ConfigurableApplicationContext {
        val delegate = DefaultCacheAwareContextLoaderDelegate()
        val mcc =
            MergedContextConfiguration(
                this::class.java,
                emptyArray(),
                arrayOf(configClass),
                emptySet(),
                emptyArray(),
                AnnotationConfigContextLoader(),
            )
        return delegate.loadContext(mcc) as ConfigurableApplicationContext
    }

    private fun currentCacheSize(): Int {
        val field = DefaultCacheAwareContextLoaderDelegate::class.java.getDeclaredField("defaultContextCache")
        field.isAccessible = true
        val cache = field.get(null)
        return cache.javaClass.getMethod("size").invoke(cache) as Int
    }
}
