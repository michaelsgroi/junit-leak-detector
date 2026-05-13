package com.michaelsgroi.test.extensions.resourceleak

class ThreadMonitor : DiscreteResourceMonitor {
    override val resourceType = ResourceType.THREADS

    override fun snapshot(): Set<ResourceId> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.state != Thread.State.TERMINATED }
            .filter { it.name !in ALWAYS_IGNORED }
            .map { ResourceId.ThreadId(it.name, it.id) }
            .toSet()

    companion object {
        /**
         * Threads we never report as leaks because they're spawned by the JVM/JDK or by the
         * detector itself, lazily, and have no userspace shutdown API. Exact-match by name.
         *
         * - `JFR Periodic Tasks`, `JFR Recorder Thread`: spawned by the detector's own JFR
         *   recording for thread-creation tracking.
         * - `Attach Listener`: HotSpot's lazy thread for the JVM Attach API (jstack, JaCoCo
         *   agent, profilers). No public shutdown.
         * - `VirtualThread-unblocker`: dedicated platform thread that resumes pinned virtual
         *   threads, added in JDK 24 (JEP 491 / JDK-8338383). One per JVM, lazy on first
         *   `Thread.ofVirtual()` use. No public shutdown.
         * - `Read-Poller`, `Write-Poller`: `sun.nio.ch.Poller` carrier threads that
         *   demultiplex blocking I/O for *all* virtual threads in the JVM. One pair per JVM,
         *   lazy on first virtual-thread NIO call. No public shutdown.
         * - `ForkJoinPool-1-delayScheduler`: `ForkJoinPool.commonPool()`'s lazy delay
         *   scheduler, used internally by `VirtualThread.schedule(...)` to time virtual-thread
         *   yields. The `1` is JVM-determined: `commonPool()` is constructed in
         *   `ForkJoinPool`'s static initializer before any user code runs, so it always gets
         *   pool index 1, hence the thread name `ForkJoinPool-1-delayScheduler` (see
         *   `ForkJoinPool.startDelayScheduler` line 3452). `commonPool().shutdown()` is a
         *   documented no-op (`commonPool()` javadoc, JDK 25 source line 3102: "this pool and
         *   any ongoing processing cannot be shut down").
         */
        private val ALWAYS_IGNORED =
            setOf(
                "JFR Periodic Tasks",
                "JFR Recorder Thread",
                "Attach Listener",
                "VirtualThread-unblocker",
                "Read-Poller",
                "Write-Poller",
                "ForkJoinPool-1-delayScheduler",
            )
    }
}
