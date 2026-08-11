package com.yet.bitmessage.transport.api

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.model.LinkId

sealed interface LinkCommand {
    val linkId: LinkId
    val correlationId: CorrelationId
    val generation: Generation

    data class Write(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val bytes: Bytes,
    ) : LinkCommand

    data class Close(
        override val linkId: LinkId,
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val reason: LinkCloseReason,
    ) : LinkCommand
}
