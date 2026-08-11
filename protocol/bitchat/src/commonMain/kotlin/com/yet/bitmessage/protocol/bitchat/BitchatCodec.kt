package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes

object BitchatCodec {
    fun decode(wireBytes: Bytes): DecodeResult<DecodedPacket> {
        if (wireBytes.size == 0) return DecodeResult.Failure(DecodeError.EMPTY_INPUT)

        val version = PacketVersion.of(wireBytes[0])
        if (!BitchatBaseline2026_08.canDecode(version)) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION)
        }

        return when (version.value.toInt()) {
            1 -> V1Codec.decode(wireBytes)
            2 -> V2Codec.decode(wireBytes)
            else -> DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION)
        }
    }

    fun encode(packet: DecodedPacket): EncodeResult =
        encode(packet, requireEmittable = true)

    internal fun encodeKnown(packet: DecodedPacket): EncodeResult =
        encode(packet, requireEmittable = false)

    private fun encode(packet: DecodedPacket, requireEmittable: Boolean): EncodeResult =
        when (packet.version.value.toInt()) {
            1 -> V1Codec.encode(packet, requireEmittable)
            2 -> V2Codec.encode(packet, requireEmittable)
            else -> EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
}
