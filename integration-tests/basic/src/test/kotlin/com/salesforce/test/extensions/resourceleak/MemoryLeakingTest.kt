package com.salesforce.test.extensions.resourceleak

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryLeakingTest {
    companion object {
        val leaked = mutableListOf<ByteArray>()
    }

    @Test
    fun `test that leaks memory`() {
        repeat(50) {
            leaked.add(ByteArray(1_048_576))
        }
        assertTrue(leaked.isNotEmpty())
    }
}
