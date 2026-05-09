package com.salesforce.test.extensions.resourceleak

import org.slf4j.LoggerFactory

class PortMonitor : DiscreteResourceMonitor {
    private val log = LoggerFactory.getLogger(javaClass)
    override val resourceIdClass = ResourceId.PortId::class

    override fun gatherResources(): Set<ResourceId> {
        return try {
            val pid = ProcessHandle.current().pid()
            val os = System.getProperty("os.name", "").lowercase()
            if (os.contains("mac") || os.contains("darwin")) {
                gatherPortsMacOs(pid)
            } else {
                gatherPortsLinux(pid)
            }
        } catch (e: Exception) {
            log.debug("Failed to gather port resources", e)
            emptySet()
        }
    }

    private fun gatherPortsMacOs(pid: Long): Set<ResourceId> {
        val process = ProcessBuilder("lsof", "-p", pid.toString(), "-i", "-P", "-n")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val pidStr = pid.toString()
        return output.lines()
            .filter { it.contains("LISTEN") }
            .filter { line ->
                val parts = line.trim().split("\\s+".toRegex())
                parts.size >= 2 && parts[1] == pidStr
            }
            .mapNotNull { line -> parsePortFromLsofLine(line) }
            .map { ResourceId.PortId(it) }
            .toSet()
    }

    private fun gatherPortsLinux(pid: Long): Set<ResourceId> {
        val process = ProcessBuilder("ss", "-lntp")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val pidPattern = "pid=$pid"
        return output.lines()
            .filter { it.contains(pidPattern) }
            .mapNotNull { line -> parsePortFromSsLine(line) }
            .map { ResourceId.PortId(it) }
            .toSet()
    }

    private fun parsePortFromLsofLine(line: String): Int? {
        // Format: COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME (STATE)
        // e.g.: java 92595 msgroi 5u IPv6 0xb360... 0t0 TCP *:57376 (LISTEN)
        // NAME is the second-to-last token (e.g., *:57376), STATE is last (e.g., (LISTEN))
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 10) return null
        val name = parts[parts.size - 2]
        val portStr = name.substringAfterLast(":")
        return portStr.toIntOrNull()
    }

    private fun parsePortFromSsLine(line: String): Int? {
        // Format: LISTEN 0 128 *:8080 *:* users:(("java",pid=12345,fd=10))
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 4) return null
        val localAddr = parts[3]
        val portStr = localAddr.substringAfterLast(":")
        return portStr.toIntOrNull()
    }
}
