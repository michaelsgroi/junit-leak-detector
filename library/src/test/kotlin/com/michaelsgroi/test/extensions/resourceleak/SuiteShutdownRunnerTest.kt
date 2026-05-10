package com.michaelsgroi.test.extensions.resourceleak

import com.michaelsgroi.test.extensions.LogAssertingExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SuiteShutdownRunnerTest {
    @RegisterExtension
    val logExt = LogAssertingExtension(LogAssertingExtension.Level.WARN)

    @Test
    fun `runs each registered hook in order`() {
        val invocations = mutableListOf<String>()
        val hooks =
            listOf(
                fakeHook("a") { invocations += "a" },
                fakeHook("b") { invocations += "b" },
            )
        SuiteShutdownRunner(hookProvider = { hooks }).runAll()
        assertEquals(listOf("a", "b"), invocations)
    }

    @Test
    fun `swallows exceptions from a hook and continues with the next`() {
        val invocations = mutableListOf<String>()
        val hooks =
            listOf(
                fakeHook("bad") { error("boom") },
                fakeHook("good") { invocations += "good" },
            )
        SuiteShutdownRunner(hookProvider = { hooks }).runAll()
        assertEquals(listOf("good"), invocations)
        assertTrue(
            logExt.logged(LogAssertingExtension.Level.WARN) {
                it.contains("SuiteShutdownHook 'bad' threw")
            },
        )
    }

    @Test
    fun `times out a hanging hook and proceeds`() {
        val invocations = mutableListOf<String>()
        val hooks =
            listOf(
                fakeHook("hang") {
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {
                        // expected
                    }
                },
                fakeHook("after") { invocations += "after" },
            )
        SuiteShutdownRunner(hookProvider = { hooks }, perHookTimeoutSeconds = 1L).runAll()
        assertEquals(listOf("after"), invocations)
        assertTrue(
            logExt.logged(LogAssertingExtension.Level.WARN) {
                it.contains("SuiteShutdownHook 'hang' did not complete within")
            },
        )
    }

    private fun fakeHook(
        hookName: String,
        body: () -> Unit,
    ): SuiteShutdownHook =
        object : SuiteShutdownHook {
            override val name = hookName

            override fun shutdown() = body()
        }
}
