package com.yet.bitmessage.model

import com.yet.bitmessage.foundation.Bytes
import kotlin.jvm.JvmInline

@JvmInline
value class LinkId private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): LinkId {
            require(value.isNotBlank()) { "Link ID must not be blank." }
            return LinkId(value)
        }
    }
}

@JvmInline
value class PeerId private constructor(
    val value: Bytes,
) {
    companion object {
        fun of(value: Bytes): PeerId {
            require(value.size > 0) { "Peer ID must not be empty." }
            return PeerId(value)
        }
    }
}
