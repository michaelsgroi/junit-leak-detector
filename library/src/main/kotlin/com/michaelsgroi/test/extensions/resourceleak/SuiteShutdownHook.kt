package com.michaelsgroi.test.extensions.resourceleak

/**
 * Hook invoked after the last test class completes, but before the FINAL snapshot.
 *
 * Implementations close suite-shared infrastructure that would otherwise be
 * misattributed as leaks (cached Spring `ApplicationContext`s, embedded servers,
 * connection pools, etc.). The library itself ships one default implementation
 * for Spring's TestContext cache; consumers can register additional
 * implementations via the standard `META-INF/services` mechanism.
 *
 * Hooks run sequentially in `ServiceLoader` discovery order. Each hook is
 * invoked on a worker thread with a 30-second timeout; on timeout the hook is
 * interrupted, a WARN is logged, and the next hook proceeds. Exceptions thrown
 * by a hook are caught, logged at WARN, and do not interrupt the test plan.
 *
 * After all hooks have run, the existing pre-class settle wait infrastructure
 * is reused to drain any async thread/port releases triggered by the hooks
 * (controlled by `final.settle.*` configuration keys), and only then is the
 * FINAL snapshot taken. This means resources released by shutdown hooks
 * disappear from the leak list automatically — no attribution-side change
 * required.
 */
interface SuiteShutdownHook {
    /**
     * Short identifier used in log messages (e.g., "spring", "myCustomHook").
     */
    val name: String

    /**
     * Perform the shutdown work. Should be idempotent and bounded; the library
     * applies a 30-second timeout per hook.
     */
    fun shutdown()
}
