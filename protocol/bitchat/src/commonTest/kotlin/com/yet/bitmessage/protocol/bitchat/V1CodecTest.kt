package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class V1CodecTest {
    @Test
    fun v1BroadcastLiteralDecodesAndEncodesExactly() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(v1Broadcast)).value

        assertEquals(PacketVersion.of(1u), decoded.version)
        assertEquals(PacketType.of(0x02u), decoded.type)
        assertEquals(3u.toUByte(), decoded.ttl)
        assertEquals(0x0102030405060708uL, decoded.timestamp)
        assertEquals(WirePeerId.of(bytes("0011223344556677")), decoded.sender)
        assertEquals(null, decoded.recipient)
        assertEquals(Bytes.copyOf(byteArrayOf(0x41, 0x42)), decoded.payload)
        assertEquals(v1Broadcast, decoded.rawPacket.wireBytes)
        assertEquals(EncodeResult.Success(v1Broadcast), BitchatCodec.encode(decoded))
    }

    @Test
    fun v1RecipientLiteralDecodesAndEncodesExactly() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(v1Recipient)).value

        assertEquals(WirePeerId.of(bytes("8899aabbccddeeff")), decoded.recipient)
        assertEquals(v1Recipient, assertIs<EncodeResult.Success>(BitchatCodec.encode(decoded)).bytes)
    }

    @Test
    fun v1PayloadLengthMismatchHasATypedResult() {
        val malformed = bytes("010203010203040506070800000300112233445566774142")

        assertEquals(DecodeResult.Failure(DecodeError.INVALID_LENGTH), BitchatCodec.decode(malformed))
    }

    @Test
    fun emptyAndUnsupportedVersionInputsHaveTypedResults() {
        assertEquals(DecodeResult.Failure(DecodeError.EMPTY_INPUT), BitchatCodec.decode(Bytes.copyOf(ByteArray(0))))
        assertEquals(DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION), BitchatCodec.decode(bytes("03")))
    }

    @Test
    fun everyTruncatedV1LiteralPrefixHasATypedFailure() {
        val full = v1Recipient.copyToByteArray()

        for (length in 0 until full.size) {
            val result = BitchatCodec.decode(Bytes.copyOf(full.copyOf(length)))
            assertTrue(result is DecodeResult.Failure, "prefix length $length must not decode")
        }
    }

    private companion object {
        val v1Broadcast = bytes("010203010203040506070800000200112233445566774142")
        val v1Recipient = bytes("010203010203040506070801000200112233445566778899aabbccddeeff4142")

        fun bytes(hex: String): Bytes =
            Bytes.copyOf(
                ByteArray(hex.length / 2) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                },
            )
    }
}
