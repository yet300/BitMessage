package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.TraceRecord
import com.yet.bitmessage.foundation.TraceSize
import com.yet.bitmessage.foundation.TraceSizeKind
import com.yet.bitmessage.foundation.TransitionName

enum class MeshTraceTransition(
    internal val value: String,
) {
    LINK_OPENED("mesh.link.opened"),
    LINK_READINESS("mesh.link.readiness"),
    LINK_CLOSED("mesh.link.closed"),
    PAYLOAD_RECEIVED("mesh.payload.received"),
    DECODE_RESULT("mesh.decode.result"),
    DIGEST_RESULT("mesh.digest.result"),
    SIGNATURE_RESULT("mesh.signature.result"),
    ADMISSION("mesh.admission"),
    DUPLICATE("mesh.admission.duplicate"),
    FRAGMENT("mesh.fragment"),
    RELAY("mesh.relay"),
    TIMER("mesh.timer"),
    LINK_RESULT("mesh.link.result"),
    LIFECYCLE("mesh.lifecycle"),
    STALE_RESULT("mesh.result.stale"),
    EFFECT_FAILURE("mesh.effect.failure"),
    LIMIT_REACHED("mesh.limit.reached"),
}

object MeshTrace {
    fun record(
        transition: MeshTraceTransition,
        decision: TraceDecision,
        correlationId: CorrelationId? = null,
        inputBytes: Int? = null,
        outputBytes: Int? = null,
        itemCount: Int? = null,
    ): TraceRecord {
        val sizes = buildList {
            inputBytes?.let { add(TraceSize(TraceSizeKind.INPUT_BYTES, it)) }
            outputBytes?.let { add(TraceSize(TraceSizeKind.OUTPUT_BYTES, it)) }
            itemCount?.let { add(TraceSize(TraceSizeKind.ITEM_COUNT, it)) }
        }
        return TraceRecord(
            transitionName = TransitionName.of(transition.value),
            correlationId = correlationId,
            decision = decision,
            sizeFacts = sizes,
        )
    }
}
