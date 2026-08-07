package com.yet.bitmessage.foundation

import kotlin.jvm.JvmInline

@JvmInline
value class TransitionName private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): TransitionName {
            require(value.isNotBlank()) { "Transition name must not be blank." }
            return TransitionName(value)
        }
    }
}

enum class TraceDecision {
    APPLIED,
    IGNORED,
    REJECTED,
    SCHEDULED,
}

enum class TraceSizeKind {
    INPUT_BYTES,
    OUTPUT_BYTES,
    ITEM_COUNT,
}

data class TraceSize(
    val kind: TraceSizeKind,
    val count: Int,
) {
    init {
        require(count >= 0) { "Trace size must not be negative." }
    }
}

/**
 * Structured transition facts only. Raw payloads and diagnostic values are deliberately absent.
 */
class TraceRecord(
    val transitionName: TransitionName,
    val correlationId: CorrelationId? = null,
    val decision: TraceDecision,
    sizeFacts: List<TraceSize> = emptyList(),
) {
    private val sizeFactsSnapshot = sizeFacts.toList()

    val sizeFacts: List<TraceSize>
        get() = sizeFactsSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is TraceRecord &&
            transitionName == other.transitionName &&
            correlationId == other.correlationId &&
            decision == other.decision &&
            sizeFactsSnapshot == other.sizeFactsSnapshot

    override fun hashCode(): Int {
        var result = transitionName.hashCode()
        result = 31 * result + (correlationId?.hashCode() ?: 0)
        result = 31 * result + decision.hashCode()
        result = 31 * result + sizeFactsSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "TraceRecord(transitionName=$transitionName, correlationId=$correlationId, " +
            "decision=$decision, sizeFacts=$sizeFactsSnapshot)"
}
