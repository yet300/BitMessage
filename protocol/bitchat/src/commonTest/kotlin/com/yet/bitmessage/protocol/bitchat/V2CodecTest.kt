package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class V2CodecTest {
    @Test
    fun v2BroadcastLiteralDecodesAndEncodesExactly() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(v2Broadcast)).value

        assertEquals(PacketVersion.of(2u), decoded.version)
        assertEquals(null, decoded.recipient)
        assertEquals(null, decoded.route)
        assertEquals(v2Broadcast, assertIs<EncodeResult.Success>(BitchatCodec.encode(decoded)).bytes)
    }

    @Test
    fun v2RecipientLiteralDecodesAndEncodesExactly() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(v2Recipient)).value

        assertEquals(WirePeerId.of(bytes("8899aabbccddeeff")), decoded.recipient)
        assertEquals(v2Recipient, assertIs<EncodeResult.Success>(BitchatCodec.encode(decoded)).bytes)
    }

    @Test
    fun v2RouteLiteralPreservesOrderedExactEightByteAddresses() {
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(v2Route)).value

        assertEquals(
            listOf(
                WirePeerId.of(bytes("0011223344556677")),
                WirePeerId.of(bytes("8899aabbccddeeff")),
            ),
            decoded.route?.entries,
        )
        assertEquals(v2Route, assertIs<EncodeResult.Success>(BitchatCodec.encode(decoded)).bytes)
    }

    @Test
    fun v2RejectsAResolvedOverLimitAdvertisedPayloadBeforeReadingIt() {
        val oversized = bytes("020203010203040506070800010000010011223344556677")

        assertEquals(DecodeResult.Failure(DecodeError.LIMIT_EXCEEDED), BitchatCodec.decode(oversized))
    }

    @Test
    fun v2RouteLimitIsCheckedBeforeRouteEntryAllocation() {
        val oversizedRoute = bytes("02020301020304050607080800000000001122334455667721")

        assertEquals(DecodeResult.Failure(DecodeError.LIMIT_EXCEEDED), BitchatCodec.decode(oversizedRoute))
    }

    @Test
    fun v2RouteTailThatCannotSupplyTheAdvertisedEntriesIsAnInvalidLength() {
        val invalidRouteLength = bytes("0202030102030405060708090000000200112233445566778899aabbccddeeff020011223344556677")

        assertEquals(DecodeResult.Failure(DecodeError.INVALID_LENGTH), BitchatCodec.decode(invalidRouteLength))
    }

    @Test
    fun everyTruncatedV2RoutePrefixHasATypedFailure() {
        val full = v2Route.copyToByteArray()

        for (length in 0 until full.size) {
            assertTrue(BitchatCodec.decode(Bytes.copyOf(full.copyOf(length))) is DecodeResult.Failure)
        }
    }

    private companion object {
        val v2Broadcast = bytes("0202030102030405060708000000000200112233445566774142")
        val v2Recipient = bytes("0202030102030405060708010000000200112233445566778899aabbccddeeff4142")
        val v2Route = bytes("0202030102030405060708090000000200112233445566778899aabbccddeeff0200112233445566778899aabbccddeeff4142")

        fun bytes(hex: String): Bytes =
            Bytes.copyOf(
                ByteArray(hex.length / 2) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                },
            )
    }
}
