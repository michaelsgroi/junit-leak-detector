package com.michaelsgroi.test.extensions.resourceleak

import org.slf4j.LoggerFactory
import java.util.ServiceLoader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Discovers `SuiteShutdownHook` SPI implementations via `ServiceLoader` and
 * runs each one with a per-hook timeout. Errors and timeouts are caught and
 * logged at WARN; never thrown to the caller.
 */
class SuiteShutdownRunner(
    private val hookProvider: () -> Iterable<SuiteShutdownHook> = ::loadHooks,
    private val perHookTimeoutSeconds: Long = 30L,
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

        private fun loadHooks(): Iterable<SuiteShutdownHook> = ServiceLoader.load(SuiteShutdownHook::class.java).toList()
    }
}
