package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory

open class PreclassSettleWaiter(
    private val configuration: Configuration = Configuration.instance,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    open fun waitForSettle(
        previousClassDelta: Map<ResourceType, Set<ResourceId>>,
        probe: (Set<ResourceType>) -> Map<ResourceType, Set<ResourceId>>,
    ) {
        if (previousClassDelta.isEmpty()) return
        val deltaScoped = previousClassDelta.filterKeys { it in SETTLE_TYPES }
        if (deltaScoped.isEmpty() || deltaScoped.values.all { it.isEmpty() }) return

        val maxMs = configuration.preclassSettleMaxSeconds * 1000L
        val pollMs = configuration.preclassSettlePollIntervalSeconds * 1000L
        val started = nowMillis()
        var carryover = computeCarryover(deltaScoped, probe(SETTLE_TYPES))
        while (carryover.isNotEmpty()) {
            log.debug("pre-class settle: waiting on {} carry-over resource(s): {}", carryover.size, carryover)
            val elapsed = nowMillis() - started
            if (elapsed >= maxMs) {
                log.warn(
                    "pre-class settle: max wait of {}s elapsed with {} carry-over resource(s) still present: {}",
                    configuration.preclassSettleMaxSeconds,
                    carryover.size,
                    carryover,
                )
                return
            }
            sleeper(pollMs)
            carryover = computeCarryover(deltaScoped, probe(SETTLE_TYPES))
        }
        log.debug("pre-class settle: carry-over cleared after {}ms", nowMillis() - started)
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
    }
}
