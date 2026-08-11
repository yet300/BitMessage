package com.yet.bitmessage.transport.api

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.model.LinkId

enum class LinkCloseReason {
    LOCAL_REQUEST,
    REMOTE_CLOSED,
    TRANSPORT_FAILED,
}

sealed interface LinkEvent {
    val linkId: LinkId

    data class Opened(
        override val linkId: LinkId,
        val capabilities: LinkCapabilities,
    ) : LinkEvent

    data class ReadinessChanged(
        override val linkId: LinkId,
        val capabilities: LinkCapabilities,
    ) : LinkEvent

    data class PayloadReceived(
        override val linkId: LinkId,
        val bytes: Bytes,
    ) : LinkEvent

    data class Closed(
        override val linkId: LinkId,
        val reason: LinkCloseReason,
    ) : LinkEvent
}
