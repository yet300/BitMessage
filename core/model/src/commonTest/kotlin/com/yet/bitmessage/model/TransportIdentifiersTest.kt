package com.yet.bitmessage.model

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransportIdentifiersTest {

    @Test
    fun linkIdRequiresANonblankOpaqueToken() {
        assertEquals(LinkId.of("mesh-link-1"), LinkId.of("mesh-link-1"))

        assertFailsWith<IllegalArgumentException> { LinkId.of("") }
        assertFailsWith<IllegalArgumentException> { LinkId.of(" ") }
    }

    @Test
    fun peerIdPreservesOpaqueBytesAndRejectsOnlyEmptyInput() {
        val protocolAddress = Bytes.copyOf(byteArrayOf(0, -1, 0))
        val peerId = PeerId.of(protocolAddress)
        val unconstrainedLength = PeerId.of(Bytes.copyOf(ByteArray(65)))

        assertEquals(protocolAddress, peerId.value)
        assertEquals(255.toUByte(), peerId.value[1])
        assertEquals(65, unconstrainedLength.value.size)
        assertFailsWith<IllegalArgumentException> { PeerId.of(Bytes.copyOf(byteArrayOf())) }
    }

    @Test
    fun linkAndPeerIdsUseDistinctTypedRoles() {
        val linkId = LinkId.of("mesh-link-1")
        val peerId = PeerId.of(Bytes.copyOf(byteArrayOf(1)))

        assertEquals("mesh-link-1", linkToken(linkId))
        assertEquals(1, peerAddressSize(peerId))
    }

    private fun linkToken(linkId: LinkId): String = linkId.value

    private fun peerAddressSize(peerId: PeerId): Int = peerId.value.size
}
