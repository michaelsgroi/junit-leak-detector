package com.michaelsgroi.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import java.util.ServiceLoader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Discovers `SuiteShutdownHook` SPI implementations via `ServiceLoader` and via
 * `suite.shutdown.static.methods` config (declarative `fully.qualified.Class#staticMethod`
 * entries), and runs each one with a per-hook timeout. Errors and timeouts are caught and
 * logged at WARN; never thrown to the caller.
 */
class SuiteShutdownRunner(
    private val hookProvider: () -> Iterable<SuiteShutdownHook> = { loadAllHooks() },
    private val perHookTimeoutSeconds: Long = 10L,
) {
    fun runAll() {
        val hooks = hookProvider().toList()
        if (hooks.isEmpty()) return

        for (hook in hooks) {
            runOne(hook)
        }
    }

    private fun runOne(hook: SuiteShutdownHook) {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "leak-detector-shutdown-${hook.name}") }
        try {
            val future = executor.submit { hook.shutdown() }
            try {
                future.get(perHookTimeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                log.warn(
                    "SuiteShutdownHook '{}' did not complete within {}s; interrupting and proceeding",
                    hook.name,
                    perHookTimeoutSeconds,
                )
            } catch (e: Exception) {
                log.warn("SuiteShutdownHook '{}' threw: {}", hook.name, e.cause?.message ?: e.message)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SuiteShutdownRunner::class.java)

        private fun loadAllHooks(): Iterable<SuiteShutdownHook> {
            val spi = ServiceLoader.load(SuiteShutdownHook::class.java).toList()
            val declarative =
                Configuration.instance.suiteShutdownStaticMethods.mapNotNull { entry ->
                    buildReflectiveHook(entry)
                }
            return spi + declarative
        }

        private fun buildReflectiveHook(entry: String): SuiteShutdownHook? {
            val parts = entry.split("#", limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                log.warn("Ignoring malformed suite.shutdown.static.methods entry '{}': expected 'class#method'", entry)
                return null
            }
            val className = parts[0]
            val methodName = parts[1]
            return ReflectiveStaticMethodHook(className, methodName)
        }
    }
}

/**
 * `SuiteShutdownHook` that resolves and invokes a static, no-arg method by name. Missing class or
 * method is treated as an opt-out (logged at debug, no error) so consumers without the target
 * dependency on the classpath get a silent no-op.
 */
private class ReflectiveStaticMethodHook(
    private val className: String,
    private val methodName: String,
) : SuiteShutdownHook {
    override val name: String = "$className#$methodName"

    override fun shutdown() {
        val cls =
            try {
                Class.forName(className)
            } catch (_: ClassNotFoundException) {
                log.debug("ReflectiveStaticMethodHook: class {} not found; skipping", className)
                return
            }
        val method =
            try {
                cls.getMethod(methodName)
            } catch (e: NoSuchMethodException) {
                log.warn("ReflectiveStaticMethodHook: no public no-arg static method {}#{}", className, methodName)
                return
            }
        method.invoke(null)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReflectiveStaticMethodHook::class.java)
    }
}
