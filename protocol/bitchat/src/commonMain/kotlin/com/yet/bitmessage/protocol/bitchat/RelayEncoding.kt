package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes

object RelayEncoding {
    fun withTtl(packet: DecodedPacket, outgoingTtl: UByte): EncodeResult {
        if (packet.flags.isCompressed || packet.flags.hasPadding) {
            return EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE)
        }

        val updated = packet.rawPacket.wireBytes.copyToByteArray()
        if (updated.size <= TTL_OFFSET) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        updated[TTL_OFFSET] = outgoingTtl.toByte()

        val bytes = Bytes.copyOf(updated)
        val decoded = when (val result = BitchatCodec.decode(bytes)) {
            is DecodeResult.Success -> result.value
            is DecodeResult.Failure -> return EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
        val restored = decoded.copy(ttl = packet.ttl, rawPacket = packet.rawPacket)
        return if (restored == packet) {
            EncodeResult.Success(bytes)
        } else {
            EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
    }

    private const val TTL_OFFSET: Int = 2
}
