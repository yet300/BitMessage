package com.yet.bitmessage.transport.api

data class LinkCapabilities(
    val maxWriteBytes: Int,
    val writeReady: Boolean,
) {
    init {
        require(maxWriteBytes > 0) { "Maximum write size must be positive." }
    }
}
