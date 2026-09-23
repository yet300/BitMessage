package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime

enum class TraceCategory {
    SCHEDULED,
    PAYLOAD_DELIVERED,
    DECODE_REJECTED,
    ASYNC_RESULT,
    LINK_OBSERVED,
    TIMER_FIRED,
    FAULT_APPLIED,
    WRITE_COMPLETED,
    ADMITTED,
    REINJECTED,
    PUBLICATION,
}

enum class TraceOutcome {
    SCHEDULED,
    DELIVERED,
    REJECTED,
    APPLIED,
    COMPLETED,
    FAILED,
    WRITTEN_WHILE_CLOSED,
}

/** Metadata only. Raw wire bytes, payload content, and secrets cannot be represented here. */
data class SimulationTraceRecord(
    val time: MonotonicTime,
    val sequence: Long,
    val nodeId: SimulatedNodeId? = null,
    val linkId: SimulatedLinkId? = null,
    val category: TraceCategory,
    val packetIdPrefix: String? = null,
    val byteCount: Int? = null,
    val ttl: UByte? = null,
    val generation: Generation? = null,
    val correlationId: CorrelationId? = null,
    val outcome: TraceOutcome,
) {
    init {
        require(sequence >= 0)
        require(byteCount == null || byteCount >= 0)
        require(packetIdPrefix == null || (
            packetIdPrefix.isNotEmpty() && packetIdPrefix.length <= 8 &&
                packetIdPrefix.all { it in '0'..'9' || it in 'a'..'f' }
            ))
    }
}

class BoundedSimulationTrace(
    private val capacity: Int,
    initialDroppedCount: Long = 0,
) {
    init {
        require(capacity > 0)
        require(initialDroppedCount >= 0)
    }

    private val retained = ArrayDeque<SimulationTraceRecord>()
    var droppedCount: Long = initialDroppedCount
        private set

    fun append(record: SimulationTraceRecord) {
        if (retained.size == capacity) {
            retained.removeFirst()
            if (droppedCount < Long.MAX_VALUE) droppedCount++
        }
        retained.addLast(record)
    }

    fun records(): List<SimulationTraceRecord> = retained.toList()
}
