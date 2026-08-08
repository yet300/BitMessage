package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes

/** No Phase 1 fixture supplies a canonical signing transcript for this profile. */
object SigningTranscript {
    fun build(packet: DecodedPacket): DecodeResult<Bytes> =
        DecodeResult.Failure(DecodeError.PROFILE_VIOLATION)
}
