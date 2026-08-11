package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Phase4ProtocolBehaviorTest {
    @Test
    fun malformedFragmentMetadataHasTypedFailures() {
        val truncated = ByteArray(FragmentPayloadCodec.HEADER_BYTES - 1)
        val zeroCount = ByteArray(FragmentPayloadCodec.HEADER_BYTES).apply {
            this[12] = KnownPacketType.MESSAGE.value.toByte()
        }
        val outOfRange = ByteArray(FragmentPayloadCodec.HEADER_BYTES).apply {
            this[9] = 2
            this[11] = 2
            this[12] = KnownPacketType.MESSAGE.value.toByte()
        }

        assertEquals(
            DecodeResult.Failure(DecodeError.TRUNCATED),
            FragmentPayloadCodec.decode(Bytes.copyOf(truncated)),
        )
        assertEquals(
            DecodeResult.Failure(DecodeError.MALFORMED_FIELD),
            FragmentPayloadCodec.decode(Bytes.copyOf(zeroCount)),
        )
        assertEquals(
            DecodeResult.Failure(DecodeError.MALFORMED_FIELD),
            FragmentPayloadCodec.decode(Bytes.copyOf(outOfRange)),
        )
    }

    @Test
    fun fragmentTypeHasExplicitAdmissionWithoutOrdinaryEmissionOrOuterRelay() {
        val unsignedMessage = baselineMessage()
        val unsignedFragment = unsignedMessage.copy(
            type = PacketType.of(KnownPacketType.FRAGMENT.value),
            payload = Bytes.copyOf(byteArrayOf(1)),
        )
        val signedFragment = unsignedFragment.copy(
            flags = PacketFlags.of(PacketFlags.SIGNATURE_BIT.toUByte()),
            signature = Bytes.copyOf(ByteArray(64) { 0x5a }),
        )

        assertEquals(KnownPacketType.FRAGMENT, unsignedFragment.type.knownType)
        assertEquals(false, BitchatBaseline2026_08.canEmit(unsignedFragment.type))
        assertEquals(false, BitchatBaseline2026_08.canRelay(unsignedFragment.type))
        assertEquals(
            EncodeResult.Failure(EncodeError.PROFILE_VIOLATION),
            BitchatCodec.encode(unsignedFragment),
        )
        assertIs<DecodeResult.Success<Bytes>>(SigningTranscript.build(signedFragment))
        assertEquals(
            PacketAdmissionPolicy.ALLOW_UNSIGNED_MESSAGE,
            BitchatBaseline2026_08.admissionPolicy(unsignedMessage),
        )
        assertEquals(
            PacketAdmissionPolicy.ALLOW_UNSIGNED_FRAGMENT,
            BitchatBaseline2026_08.admissionPolicy(unsignedFragment),
        )
        assertEquals(
            PacketAdmissionPolicy.VERIFY_SIGNATURE,
            BitchatBaseline2026_08.admissionPolicy(signedFragment),
        )
        assertEquals(
            PacketAdmissionPolicy.REJECT,
            BitchatBaseline2026_08.admissionPolicy(unsignedMessage.copy(type = PacketType.of(0x7fu))),
        )
    }

    @Test
    fun signingAndRelayKeepUnresolvedCompressionAndPaddingBlocked() {
        val message = baselineMessage()
        val compressed = message.copy(flags = PacketFlags.of(PacketFlags.COMPRESSED_BIT.toUByte()))
        val padded = message.copy(flags = PacketFlags.of(PacketFlags.PADDING_BIT.toUByte()))

        listOf(compressed, padded).forEach { packet ->
            assertEquals(
                DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE),
                SigningTranscript.build(packet),
            )
            assertEquals(
                EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE),
                RelayEncoding.withTtl(packet, 2u),
            )
        }
    }

    private fun baselineMessage(): DecodedPacket =
        assertIs<DecodeResult.Success<DecodedPacket>>(
            BitchatCodec.decode(bytes("0202030102030405060708000000000200112233445566774142")),
        ).value

    private fun bytes(hex: String): Bytes =
        Bytes.copyOf(
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            },
        )
}
