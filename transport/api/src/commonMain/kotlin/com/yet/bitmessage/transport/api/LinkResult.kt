package com.yet.bitmessage.transport.api

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.model.LinkId

enum class LinkFailureCode {
    TRANSIENT,
    PERMANENT,
    CANCELLED,
}

sealed interface LinkResult {
    val linkId: LinkId
    val correlationId: CorrelationId
    val generation: Generation

    data class Written(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
    ) : LinkResult

    data class Backpressured(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
    ) : LinkResult

    data class PayloadTooLarge(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val maximumBytes: Int,
    ) : LinkResult

    data class Disconnected(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
    ) : LinkResult

    data class Unsupported(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
    ) : LinkResult

    data class Failed(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val code: LinkFailureCode,
    ) : LinkResult
}
