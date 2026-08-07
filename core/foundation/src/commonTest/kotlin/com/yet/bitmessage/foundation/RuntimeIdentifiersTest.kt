package com.yet.bitmessage.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeIdentifiersTest {

    @Test
    fun timerAndCorrelationIdsRequireExplicitNonblankTokens() {
        assertEquals(TimerId.of("retry"), TimerId.of("retry"))
        assertEquals(CorrelationId.of("entropy-request"), CorrelationId.of("entropy-request"))

        assertFailsWith<IllegalArgumentException> { TimerId.of("") }
        assertFailsWith<IllegalArgumentException> { TimerId.of(" ") }
        assertFailsWith<IllegalArgumentException> { CorrelationId.of("") }
        assertFailsWith<IllegalArgumentException> { CorrelationId.of("\t") }
    }

    @Test
    fun generationIsNeutralNonnegativeAndCannotOverflow() {
        assertEquals(Generation(0), Generation(0))
        assertEquals(Generation(1), Generation(0).next())

        assertFailsWith<IllegalArgumentException> { Generation(-1) }
        assertFailsWith<IllegalStateException> { Generation(Long.MAX_VALUE).next() }
    }
}
