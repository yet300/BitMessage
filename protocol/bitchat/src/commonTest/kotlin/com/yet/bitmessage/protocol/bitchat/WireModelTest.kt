package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WireModelTest {
    @Test
    fun wirePeerRequiresExactlyEightOpaqueProtocolBytes() {
        val eight = Bytes.copyOf(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))

        assertEquals(eight, WirePeerId.of(eight).value.value)
        assertFailsWith<IllegalArgumentException> { WirePeerId.of(Bytes.copyOf(ByteArray(7))) }
        assertFailsWith<IllegalArgumentException> { WirePeerId.of(Bytes.copyOf(ByteArray(9))) }
    }

    @Test
    fun packetTypeRetainsUnknownNumericValues() {
        assertEquals(0xffu, PacketType.of(0xffu).value)
        assertEquals(null, PacketType.of(0xffu).knownType)
    }

    @Test
    fun rawPacketRetainsAnOwnedImmutableBytesValue() {
        val source = byteArrayOf(1, 2, 3)
        val raw = RawPacket(Bytes.copyOf(source))
        source[0] = 99

        assertEquals(Bytes.copyOf(byteArrayOf(1, 2, 3)), raw.wireBytes)
    }

    @Test
    fun limitsRejectInvalidBoundsWithoutNormalizingThem() {
        assertFailsWith<IllegalArgumentException> { DecodeLimits(maxPayloadBytes = -1, maxRouteEntries = 1) }
        assertFailsWith<IllegalArgumentException> { DecodeLimits(maxPayloadBytes = 1, maxRouteEntries = -1) }
    }

    @Test
    fun profileUsesTheResolvedHostileInputCeilings() {
        assertEquals(16 * 1024 * 1024, BitchatBaseline2026_08.decodeLimits.maxPayloadBytes)
        assertEquals(32, BitchatBaseline2026_08.decodeLimits.maxRouteEntries)
    }

    @Test
    fun profileDoesNotPermitUnknownOrDecodeOnlyTypeEmission() {
        assertFalse(BitchatBaseline2026_08.canEmit(PacketType.of(0x2cu)))
        assertFalse(BitchatBaseline2026_08.canEmit(PacketType.of(0xffu)))
    }
}
