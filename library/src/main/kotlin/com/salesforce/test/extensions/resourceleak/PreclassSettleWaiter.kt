package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory

/**
 * Polls a probe function for slow-to-release resources (threads, ports) until they
 * clear or a timeout elapses.
 *
 * Reused for two purposes:
 *   - Pre-class settle: at BeforeAll, wait for the previous class's slow releases
 *     to finish so they don't widen the next class's candidate window.
 *   - Final settle: at suite end (after suite-shutdown hooks fire), wait for
 *     async releases triggered by hooks to finish before taking the FINAL snapshot.
 *
 * The two use cases supply different timeout/poll values via constructor args.
 */
open class PreclassSettleWaiter(
    private val maxSeconds: Long,
    private val pollIntervalSeconds: Long,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val label: String = "pre-class settle",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    open fun waitForSettle(
        previousClassDelta: Map<ResourceType, Set<ResourceId>>,
        probe: (Set<ResourceType>) -> Map<ResourceType, Set<ResourceId>>,
    ) {
        if (previousClassDelta.isEmpty()) return
        val deltaScoped = previousClassDelta.filterKeys { it in SETTLE_TYPES }
        if (deltaScoped.isEmpty() || deltaScoped.values.all { it.isEmpty() }) return

        val maxMs = maxSeconds * 1000L
        val pollMs = pollIntervalSeconds * 1000L
        val started = nowMillis()
        var carryover = computeCarryover(deltaScoped, probe(SETTLE_TYPES))
        while (carryover.isNotEmpty()) {
            log.debug("{}: waiting on {} carry-over resource(s): {}", label, carryover.size, carryover)
            val elapsed = nowMillis() - started
            if (elapsed >= maxMs) {
                log.warn(
                    "{}: max wait of {}s elapsed with {} carry-over resource(s) still present: {}",
                    label,
                    maxSeconds,
                    carryover.size,
                    carryover,
                )
                return
            }
            sleeper(pollMs)
            carryover = computeCarryover(deltaScoped, probe(SETTLE_TYPES))
        }
        log.debug("{}: carry-over cleared after {}ms", label, nowMillis() - started)
    }

    private fun computeCarryover(
        previousClassDelta: Map<ResourceType, Set<ResourceId>>,
        currentSnapshot: Map<ResourceType, Set<ResourceId>>,
    ): Set<ResourceId> {
        val out = mutableSetOf<ResourceId>()
        for ((type, deltaIds) in previousClassDelta) {
            val current = currentSnapshot[type] ?: emptySet()
            out += deltaIds intersect current
        }
        return out
    }

    companion object {
        private val SETTLE_TYPES = setOf(ResourceType.THREADS, ResourceType.PORTS)

        /** Build a pre-class waiter from configuration. */
        fun forPreclass(configuration: Configuration = Configuration.instance): PreclassSettleWaiter =
            PreclassSettleWaiter(
                maxSeconds = configuration.preclassSettleMaxSeconds,
                pollIntervalSeconds = configuration.preclassSettlePollIntervalSeconds,
                label = "pre-class settle",
            )

        /** Build a final-settle waiter from configuration. */
        fun forFinal(configuration: Configuration = Configuration.instance): PreclassSettleWaiter =
            PreclassSettleWaiter(
                maxSeconds = configuration.finalSettleMaxSeconds,
                pollIntervalSeconds = configuration.finalSettlePollIntervalSeconds,
                label = "final settle",
            )
    }
}
