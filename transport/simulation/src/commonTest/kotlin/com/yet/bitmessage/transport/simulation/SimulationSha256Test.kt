package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationSha256Test {
    @Test
    fun standardKnownAnswersMatchSha256() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SimulationSha256.digest(Bytes.copyOf(byteArrayOf())).hex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SimulationSha256.digest(Bytes.copyOf(byteArrayOf(0x61, 0x62, 0x63))).hex(),
        )
    }

    @Test
    fun packetIdentityUsesTheRealDigestAndPhaseFourTruncation() {
        val digest = SimulationSha256.digest(SimulationFixtures.broadcastIdentityInput.canonicalBytes)
        val expected = digest.copyToByteArray().copyOfRange(0, PacketId.BYTE_SIZE)

        assertEquals(expected.hex(), PacketIdentity.fromSha256(digest).value.hex())
    }
}

internal fun Bytes.hex(): String = copyToByteArray().hex()

internal fun ByteArray.hex(): String =
    joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
