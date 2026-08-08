package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes

/** Received compressed bytes retained without attempting decompression or recompression. */
data class CompressionEnvelope(
    val encodedPayload: Bytes,
)
