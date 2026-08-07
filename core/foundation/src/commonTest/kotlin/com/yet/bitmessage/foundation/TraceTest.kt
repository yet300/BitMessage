package com.yet.bitmessage.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TraceTest {

    @Test
    fun traceCarriesTypedFactsWithoutRawEntropyBytes() {
        val knownBytes = byteArrayOf(0x16, 0x27, 0x38, 0x49)
        val entropy = EntropyGenerated(
            correlationId = CorrelationId.of("entropy-request"),
            bytes = Bytes.copyOf(knownBytes),
        )
        val sizeFacts = mutableListOf(TraceSize(TraceSizeKind.OUTPUT_BYTES, entropy.bytes.size))

        val trace = TraceRecord(
            transitionName = TransitionName.of("entropy-generated"),
            correlationId = entropy.correlationId,
            decision = TraceDecision.APPLIED,
            sizeFacts = sizeFacts,
        )
        sizeFacts.clear()

        assertEquals(TransitionName.of("entropy-generated"), trace.transitionName)
        assertEquals(entropy.correlationId, trace.correlationId)
        assertEquals(TraceDecision.APPLIED, trace.decision)
        assertEquals(listOf(TraceSize(TraceSizeKind.OUTPUT_BYTES, 4)), trace.sizeFacts)
        assertFalse(trace.toString().contains(knownBytes.contentToString()))
    }

    @Test
    fun traceNamesAndSizeFactsRejectInvalidValues() {
        assertFailsWith<IllegalArgumentException> { TransitionName.of(" ") }
        assertFailsWith<IllegalArgumentException> { TraceSize(TraceSizeKind.INPUT_BYTES, -1) }
    }

    @Test
    fun traceRetainsSizeFactsWhenAnExposedListIsMutable() {
        val trace = TraceRecord(
            transitionName = TransitionName.of("entropy-generated"),
            decision = TraceDecision.APPLIED,
            sizeFacts = listOf(
                TraceSize(TraceSizeKind.OUTPUT_BYTES, 4),
                TraceSize(TraceSizeKind.ITEM_COUNT, 1),
            ),
        )

        attemptMutation(trace.sizeFacts, TraceSize(TraceSizeKind.OUTPUT_BYTES, 8))

        assertEquals(
            listOf(
                TraceSize(TraceSizeKind.OUTPUT_BYTES, 4),
                TraceSize(TraceSizeKind.ITEM_COUNT, 1),
            ),
            trace.sizeFacts,
        )
    }

    private fun <T> attemptMutation(values: List<T>, value: T) {
        (values as? MutableList<T>)?.add(value)
    }
}
