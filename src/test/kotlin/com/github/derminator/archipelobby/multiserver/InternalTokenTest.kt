package com.github.derminator.archipelobby.multiserver

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InternalTokenTest {

    @Test
    fun `uses the configured token when one is set`() {
        val token = InternalToken(MultiServerProperties(internalToken = "fixed-secret"))
        assertEquals("fixed-secret", token.value)
    }

    @Test
    fun `generates a distinct non-blank token per instance when unset`() {
        val first = InternalToken(MultiServerProperties())
        val second = InternalToken(MultiServerProperties())
        assertTrue(first.value.isNotBlank())
        assertTrue(second.value.isNotBlank())
        assertTrue(first.value != second.value, "random per-process tokens should differ")
    }
}
