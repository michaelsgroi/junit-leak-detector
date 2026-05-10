package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext

/**
 * Default suite-shutdown hook for Spring TestContext-based suites.
 *
 * Cached `ApplicationContext`s outlive the test class that triggered their
 * load — that's the whole point of `@SpringBootTest` caching. Threads and
 * listening ports owned by those contexts therefore appear as "leaked from
 * class X" when X is just the first class to use a given config. Closing
 * the cached contexts before the FINAL snapshot lets those threads/ports
 * release naturally, and they drop out of the leak list.
 *
 * Spring is a `provided`-scope dependency: the library compiles against the
 * TestContext API but doesn't pull Spring transitively. The probe in
 * [shutdown] uses `Class.forName` so consumers without Spring on the test
 * classpath get a silent no-op instead of `NoClassDefFoundError` when the
 * SPI loads this class.
 */
class SpringContextShutdownHook : SuiteShutdownHook {
    override val name: String = "spring"

    override fun shutdown() {
        // Probe before referencing Spring types: protects consumers without Spring.
        try {
            Class.forName(DELEGATE_CLASS)
        } catch (_: ClassNotFoundException) {
            log.debug("SpringContextShutdownHook: Spring TestContext framework not on classpath; nothing to do")
            return
        }

        SpringDelegate.shutdownAll()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpringContextShutdownHook::class.java)
        private const val DELEGATE_CLASS = "org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate"
    }
}

/**
 * Real-Spring-typed worker. Kept in a separate object so [SpringContextShutdownHook]
 * can probe for Spring's presence before the JVM links any of these references.
 *
 * `ContextCache.clear()` empties the cache map but does NOT close the underlying
 * `ApplicationContext`s — their threads keep running. We reach in to the
 * `DefaultContextCache.contextMap` (package-private) and call `close()` on each
 * `ConfigurableApplicationContext` ourselves before clearing.
 */
private object SpringDelegate {
    private val log = LoggerFactory.getLogger(SpringContextShutdownHook::class.java)

    fun shutdownAll() {
        val cache = readDefaultCache() ?: return

        val contexts = readContexts(cache)
        if (contexts.isEmpty()) {
            log.debug("SpringContextShutdownHook: cache is empty; nothing to close")
            return
        }

        log.info("SpringContextShutdownHook: closing {} cached Spring context(s)", contexts.size)
        val started = System.currentTimeMillis()
        var closed = 0
        for (ctx in contexts) {
            try {
                if (ctx.isActive) {
                    ctx.close()
                    closed++
                }
            } catch (e: Exception) {
                log.warn("SpringContextShutdownHook: close() threw on {}: {}", ctx.javaClass.name, e.message)
            }
        }
        try {
            cache.clear()
        } catch (e: Exception) {
            log.warn("SpringContextShutdownHook: cache.clear() threw: {}", e.message)
        }
        val elapsedMs = System.currentTimeMillis() - started
        log.info("SpringContextShutdownHook: closed {} context(s) in {}ms", closed, elapsedMs)
    }

    private fun readDefaultCache(): org.springframework.test.context.cache.ContextCache? {
        val delegateClass = org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate::class.java
        val field =
            try {
                delegateClass.getDeclaredField("defaultContextCache")
            } catch (e: NoSuchFieldException) {
                log.warn("SpringContextShutdownHook: defaultContextCache field not found on {}", delegateClass.name)
                return null
            }
        field.isAccessible = true
        return field.get(null) as? org.springframework.test.context.cache.ContextCache
    }

    private fun readContexts(cache: org.springframework.test.context.cache.ContextCache): List<ConfigurableApplicationContext> {
        val field =
            try {
                cache.javaClass.getDeclaredField("contextMap")
            } catch (e: NoSuchFieldException) {
                log.warn("SpringContextShutdownHook: contextMap field not found on {}", cache.javaClass.name)
                return emptyList()
            }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(cache) as? Map<Any, Any> ?: return emptyList()
        // Snapshot under the cache's monitor so we don't iterate a concurrently-modified map.
        synchronized(map) {
            return map.values.filterIsInstance<ConfigurableApplicationContext>().toList()
        }
    }
}
