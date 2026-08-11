package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class Phase4ProtocolEvidenceTest {
    @Test
    fun packetIdentityMatchesBothPinnedClients() {
        val packet = decode("0202030102030405060708000000000200112233445566774142")

        assertEquals(
            bytes("02001122334455667701020304050607084142"),
            PacketIdentity.input(packet).canonicalBytes,
        )
        assertEquals(
            PacketId.of(bytes("25429fbd15e2051049307f8e650ae863")),
            PacketIdentity.fromSha256(
                bytes("25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda"),
            ),
        )
    }

    @Test
    fun signingTranscriptMatchesBothPinnedClients() {
        val packet = decode(signedTtlSeven)
        val expectedCore = bytes("0202000102030405060708000000000200112233445566774142")
        val expected = Bytes.copyOf(
            expectedCore.copyToByteArray() + ByteArray(230) { 0xe6.toByte() },
        )

        assertEquals(DecodeResult.Success(expected), SigningTranscript.build(packet))
    }

    @Test
    fun relayTtlMutationKeepsSigningTranscriptAndSignature() {
        val packet = decode(signedTtlSeven)
        val relayed = assertIs<EncodeResult.Success>(RelayEncoding.withTtl(packet, 6u)).bytes
        val relayedPacket = decode(relayed)

        assertEquals(signedTtlSix, relayed)
        assertEquals(6u.toUByte(), relayedPacket.ttl)
        assertEquals(6u.toUByte(), relayed[2])
        assertEquals(packet.signature, relayedPacket.signature)
        assertEquals(SigningTranscript.build(packet), SigningTranscript.build(relayedPacket))
    }

    @Test
    fun fragmentMetadataAndCompleteReassemblyLiteralsMatchBothPinnedClients() {
        val first = assertIs<DecodeResult.Success<FragmentPayload>>(
            FragmentPayloadCodec.decode(fragmentZero),
        ).value
        val second = assertIs<DecodeResult.Success<FragmentPayload>>(
            FragmentPayloadCodec.decode(fragmentOne),
        ).value

        assertEquals(FragmentId.of(bytes("0001020304050607")), first.id)
        assertEquals(0u.toUShort(), first.index)
        assertEquals(2u.toUShort(), first.total)
        assertEquals(PacketType.of(0x02u), first.originalType)
        assertEquals(fragmentZero, assertIs<EncodeResult.Success>(FragmentPayloadCodec.encode(first)).bytes)
        assertEquals(fragmentOne, assertIs<EncodeResult.Success>(FragmentPayloadCodec.encode(second)).bytes)
        assertEquals(
            bytes("0202030102030405060708000000000200112233445566774142"),
            Bytes.copyOf(first.data.copyToByteArray() + second.data.copyToByteArray()),
        )
    }

    @Test
    fun malformedFragmentMetadataHasTypedFailures() {
        assertEquals(
            DecodeResult.Failure(DecodeError.TRUNCATED),
            FragmentPayloadCodec.decode(bytes("000102030405060700010002")),
        )
        assertEquals(
            DecodeResult.Failure(DecodeError.MALFORMED_FIELD),
            FragmentPayloadCodec.decode(bytes("00010203040506070000000002")),
        )
        assertEquals(
            DecodeResult.Failure(DecodeError.MALFORMED_FIELD),
            FragmentPayloadCodec.decode(bytes("00010203040506070002000202")),
        )
    }

    @Test
    fun fragmentTypeHasExplicitAdmissionWithoutOrdinaryEmissionOrOuterRelay() {
        val unsignedMessage = decode("0202030102030405060708000000000200112233445566774142")
        val unsignedFragment = decode(fragmentPacket)
        val signedFragment = decode(signedFragmentPacket)

        assertEquals(KnownPacketType.FRAGMENT, unsignedFragment.type.knownType)
        assertFalse(BitchatBaseline2026_08.canEmit(unsignedFragment.type))
        assertFalse(BitchatBaseline2026_08.canRelay(unsignedFragment.type))
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
        val compressed = decode("0202030102030405060708040000000200112233445566774142")
        val padded = decode("0202030102030405060708000000000200112233445566774142").copy(
            flags = PacketFlags.of(PacketFlags.PADDING_BIT.toUByte()),
        )

        assertEquals(
            DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE),
            SigningTranscript.build(compressed),
        )
        assertEquals(
            DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE),
            SigningTranscript.build(padded),
        )
        assertEquals(
            EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE),
            RelayEncoding.withTtl(compressed, 2u),
        )
        assertEquals(
            EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE),
            RelayEncoding.withTtl(padded, 2u),
        )
    }

    private fun decode(hex: String): DecodedPacket = decode(bytes(hex))

    private fun decode(wireBytes: Bytes): DecodedPacket =
        assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wireBytes)).value

    private companion object {
        val signedTtlSeven: Bytes = Bytes.copyOf(
            bytes("0202070102030405060708020000000200112233445566774142").copyToByteArray() +
                ByteArray(64) { 0x5a },
        )
        val signedTtlSix: Bytes = Bytes.copyOf(
            bytes("0202060102030405060708020000000200112233445566774142").copyToByteArray() +
                ByteArray(64) { 0x5a },
        )
        val fragmentZero: Bytes = bytes("0001020304050607000000020202020301020304050607080000")
        val fragmentOne: Bytes = bytes("0001020304050607000100020200000200112233445566774142")
        val fragmentPacket: Bytes = Bytes.copyOf(
            bytes("0220030102030405060708000000001a0011223344556677").copyToByteArray() +
                fragmentZero.copyToByteArray(),
        )
        val signedFragmentPacket: Bytes = Bytes.copyOf(
            bytes("0220030102030405060708020000001a0011223344556677").copyToByteArray() +
                fragmentZero.copyToByteArray() + ByteArray(64) { 0x5a },
        )

        fun bytes(hex: String): Bytes =
            Bytes.copyOf(
                ByteArray(hex.length / 2) { index ->
                    hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                },
            )
    }
}
