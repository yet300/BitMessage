package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RawRetentionTest {
    @Test
    fun compressedFlagPacketRetainsItsExactRawCompressedRepresentation() {
        val wire = bytes("0202030102030405060708040000000200112233445566774142")
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value

        assertEquals(wire, decoded.rawPacket.wireBytes)
        assertEquals(bytes("4142"), decoded.payload)
        assertEquals(bytes("4142"), decoded.compressionEnvelope?.encodedPayload)
    }

    @Test
    fun signedPacketNeverTreatsAShortTrailingFieldAsPayload() {
        val shortSignature = bytes("0202030102030405060708020000000200112233445566774142aabb")

        assertEquals(DecodeResult.Failure(DecodeError.TRUNCATED), BitchatCodec.decode(shortSignature))
    }

    @Test
    fun signedPacketRetainsTheReceivedSignatureBytesWithoutVerifyingThem() {
        val headerAndPayload = bytes("0202030102030405060708020000000200112233445566774142")
        val signature = Bytes.copyOf(ByteArray(64) { 0x5a })
        val wire = Bytes.copyOf(headerAndPayload.copyToByteArray() + signature.copyToByteArray())

        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value
        assertEquals(signature, decoded.signature)
        assertEquals(wire, decoded.rawPacket.wireBytes)
    }

    @Test
    fun profileRefusesToEmitCompressionOrPaddingWithoutCanonicalEvidence() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(
            BitchatCodec.decode(bytes("0202030102030405060708000000000200112233445566774142")),
        ).value

        assertEquals(EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE), BitchatCodec.encode(decoded.copy(flags = PacketFlags.of(0x04u))))
        assertEquals(EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE), BitchatCodec.encode(decoded.copy(flags = PacketFlags.of(0x10u))))
    }

    @Test
    fun signingTranscriptIsExplicitlyBlockedWithoutLiteralTranscriptEvidence() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(
            BitchatCodec.decode(bytes("0202030102030405060708000000000200112233445566774142")),
        ).value

        assertEquals(DecodeResult.Failure(DecodeError.PROFILE_VIOLATION), SigningTranscript.build(decoded))
    }

    private fun bytes(hex: String): Bytes =
        Bytes.copyOf(
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            },
        )
}
