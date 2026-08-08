package com.yet.bitmessage.protocol.bitchat.payload

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AnnouncementCodecTest {
    @Test
    fun legacyAnnouncementLiteralDecodesAndReencodesExactly() {
        val decoded = assertIs<DecodeResult.Success<AnnouncementPayload>>(AnnouncementCodec.decode(legacy)).value

        assertEquals(bytes("70656572"), decoded.nickname)
        assertEquals(Bytes.copyOf(ByteArray(32) { 0x11 }), decoded.noisePublicKey)
        assertEquals(Bytes.copyOf(ByteArray(32) { 0x22 }), decoded.signingPublicKey)
        assertEquals(emptyList(), decoded.directNeighbors)
        assertNull(decoded.capabilities)
        assertEquals(listOf<UByte>(0x01u, 0x02u, 0x03u), decoded.tlvs.map { it.type })
        assertEquals(EncodeResult.Success(legacy), AnnouncementCodec.encode(decoded))
    }

    @Test
    fun extendedFormsPreserveReceivedTlvOrderAndCapabilitiesLow64() {
        val apple = assertIs<DecodeResult.Success<AnnouncementPayload>>(AnnouncementCodec.decode(extendedApple)).value
        val android = assertIs<DecodeResult.Success<AnnouncementPayload>>(AnnouncementCodec.decode(extendedAndroid)).value

        assertEquals(listOf<UByte>(0x01u, 0x02u, 0x03u, 0x04u, 0x05u), apple.tlvs.map { it.type })
        assertEquals(listOf<UByte>(0x01u, 0x02u, 0x03u, 0x05u, 0x04u), android.tlvs.map { it.type })
        assertEquals(0x8100uL, apple.capabilities?.low64)
        assertEquals(listOf(WirePeerId.of(bytes("0102030405060708"))), apple.directNeighbors)
        assertEquals(EncodeResult.Success(extendedApple), AnnouncementCodec.encode(apple))
        assertEquals(EncodeResult.Success(extendedAndroid), AnnouncementCodec.encode(android))
    }

    @Test
    fun unknownTlvIsRetainedWithoutBeingAssignedNewSemantics() {
        val payload = Bytes.copyOf(legacy.copyToByteArray() + byteArrayOf(0x7f, 0x02, 0x55, 0x66))
        val decoded = assertIs<DecodeResult.Success<AnnouncementPayload>>(AnnouncementCodec.decode(payload)).value

        assertEquals(listOf(AnnouncementTlv(0x7fu, bytes("5566"))), decoded.unknownTlvs)
        assertEquals(EncodeResult.Success(payload), AnnouncementCodec.encode(decoded))
    }

    @Test
    fun duplicateTlvIsRejectedWithATypedFailure() {
        assertEquals(
            DecodeResult.Failure(DecodeError.MALFORMED_FIELD),
            AnnouncementCodec.decode(bytes("010161010162")),
        )
    }

    @Test
    fun truncatedAndOverlengthTlvFieldsHaveTypedFailures() {
        assertEquals(DecodeResult.Failure(DecodeError.TRUNCATED), AnnouncementCodec.decode(bytes("01")))
        assertEquals(DecodeResult.Failure(DecodeError.INVALID_LENGTH), AnnouncementCodec.decode(bytes("010270")))
    }

    private companion object {
        val legacy = bytes("0104706565720220111111111111111111111111111111111111111111111111111111111111111103202222222222222222222222222222222222222222222222222222222222222222")
        val extendedApple = bytes("01047065657202201111111111111111111111111111111111111111111111111111111111111111032022222222222222222222222222222222222222222222222222222222222222220408010203040506070805020081")
        val extendedAndroid = bytes("01047065657202201111111111111111111111111111111111111111111111111111111111111111032022222222222222222222222222222222222222222222222222222222222222220502008104080102030405060708")

        fun bytes(hex: String): Bytes =
            Bytes.copyOf(
                ByteArray(hex.length / 2) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                },
            )
    }
}
