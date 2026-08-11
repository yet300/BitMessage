package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes

/**
 * Canonical known answers are fixture IDs `apple-phase4-signing-relay` and
 * `android-phase4-signing-relay` in `BitchatBaseline2026_08`.
 */
object SigningTranscript {
    fun build(packet: DecodedPacket): DecodeResult<Bytes> {
        if (packet.flags.isCompressed || packet.flags.hasPadding) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE)
        }

        val flags = PacketFlags.of(
            (packet.flags.value.toUInt() and PacketFlags.SIGNATURE_BIT.inv()).toUByte(),
        )
        return when (
            val encoded = BitchatCodec.encodeKnown(
                packet.copy(ttl = 0u, flags = flags, signature = null),
            )
        ) {
            is EncodeResult.Success -> DecodeResult.Success(SigningPadding.apply(encoded.bytes))
            is EncodeResult.Failure -> DecodeResult.Failure(DecodeError.PROFILE_VIOLATION)
        }
    }
}

private object SigningPadding {
    private val blockSizes = intArrayOf(256, 512, 1024, 2048)

    fun apply(bytes: Bytes): Bytes {
        val targetSize = blockSizes.firstOrNull { bytes.size + ENCRYPTION_OVERHEAD_BYTES <= it }
            ?: bytes.size
        val paddingSize = targetSize - bytes.size
        if (paddingSize !in 1..UByte.MAX_VALUE.toInt()) return bytes

        val output = ByteArray(targetSize)
        bytes.copyToByteArray().copyInto(output)
        output.fill(paddingSize.toByte(), fromIndex = bytes.size)
        return Bytes.copyOf(output)
    }

    private const val ENCRYPTION_OVERHEAD_BYTES: Int = 16
}
