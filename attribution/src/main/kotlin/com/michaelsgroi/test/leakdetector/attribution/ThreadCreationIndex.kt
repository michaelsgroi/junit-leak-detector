package com.michaelsgroi.test.leakdetector.attribution

import jdk.jfr.consumer.RecordingFile
import java.io.File

/**
 * Reads a `thread-creations-<ts>.jfr` file (paired with the raw report) and exposes
 * creation stacks indexed by thread name + thread id. The library's
 * `ThreadCreationRecorder` writes these recordings during `testPlanExecutionStarted`
 * → `testPlanExecutionFinished`; this class consumes them on the attribution side.
 *
 * `jdk.ThreadStart` events are the canonical "thread was started here" signal —
 * they carry the new thread's name and id (`javaThreadId`) plus the stack of the
 * thread that called `start()`, which is the stack we want for attribution.
 */
class ThreadCreationIndex private constructor(
    private val byKey: Map<Pair<String, Long>, List<String>>,
) {
    fun lookup(
        threadName: String,
        threadId: Long,
    ): List<String>? = byKey[threadName to threadId]

    val size: Int get() = byKey.size

    companion object {
        /** Empty index — used when no JFR file is available. */
        val EMPTY: ThreadCreationIndex = ThreadCreationIndex(emptyMap())

        /** Builds an in-memory index from a `(threadName, threadId) → frames` map.
         *  Public so tests can construct a ThreadCreationIndex without a real JFR file. */
        fun ofEntries(entries: Map<Pair<String, Long>, List<String>>): ThreadCreationIndex = ThreadCreationIndex(entries)

        /**
         * Parses [jfrFile] and returns an index. Failure modes (missing file,
         * unreadable, no `jdk.jfr` module on consumer JDK) yield [EMPTY] rather
         * than throwing — attribution should never fail just because JFR data
         * is missing.
         */
        fun parse(jfrFile: File): ThreadCreationIndex {
            if (!jfrFile.isFile) return EMPTY
            return try {
                val map = mutableMapOf<Pair<String, Long>, List<String>>()
                RecordingFile(jfrFile.toPath()).use { recording ->
                    while (recording.hasMoreEvents()) {
                        val event = recording.readEvent()
                        if (event.eventType.name != "jdk.ThreadStart") continue
                        val thread = event.thread ?: continue
                        val name = thread.javaName ?: continue
                        val id = thread.javaThreadId
                        val frames =
                            event.stackTrace?.frames?.map { f ->
                                val method = f.method
                                val type = method.type.name
                                val sig = method.descriptor
                                val line = if (f.lineNumber > 0) ":${f.lineNumber}" else ""
                                "$type.${method.name}$sig$line"
                            } ?: emptyList()
                        // First start event wins; if a thread name is reused later
                        // (different id), the (name,id) pair distinguishes them.
                        map.putIfAbsent(name to id, frames)
                    }
                }
                ThreadCreationIndex(map)
            } catch (e: Throwable) {
                System.err.println(
                    "Failed to parse JFR thread-creation recording ${jfrFile.absolutePath}: ${e.message}",
                )
                EMPTY
            }
        }
    }
}
