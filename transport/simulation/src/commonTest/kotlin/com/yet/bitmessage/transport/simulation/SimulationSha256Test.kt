package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SimulationSha256Test {
    @Test
    fun standardKnownAnswersMatchSha256() {
        assertSha256(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            byteArrayOf(),
        )
        assertSha256(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            byteArrayOf(0x61, 0x62, 0x63),
        )
    }

    @Test
    fun fiftySixByteStandardKnownAnswerUsesASecondPaddingBlock() {
        assertSha256(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray(),
        )
    }

    @Test
    fun oneMillionAsciiAKnownAnswerExercisesManyBlocksAndTheLargeLength() {
        assertSha256(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            ByteArray(1_000_000) { 'a'.code.toByte() },
        )
    }

    @Test
    fun everyByteValueKnownAnswerPreservesBinaryAndHighBitInput() {
        assertSha256(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            ByteArray(256) { index -> index.toByte() },
        )
    }

    @Test
    fun paddingAndBlockBoundariesMatchIndependentKnownAnswers() {
        val knownAnswers = listOf(
            55 to "463eb28e72f82e0a96c0a4cc53690c571281131f672aa229e0d45ae59b598b59",
            56 to "da2ae4d6b36748f2a318f23e7ab1dfdf45acdc9d049bd80e59de82a60895f562",
            63 to "29af2686fd53374a36b0846694cc342177e428d1647515f078784d69cdb9e488",
            64 to "fdeab9acf3710362bd2658cdc9a29e8f9c757fcf9811603a8c447cd1d9151108",
            65 to "4bfd2c8b6f1eec7a2afeb48b934ee4b2694182027e6d0fc075074f2fabb31781",
        )

        knownAnswers.forEach { (size, expected) ->
            assertSha256(expected, ByteArray(size) { index -> index.toByte() })
        }
    }

    @Test
    fun packetIdentityUsesTheRealDigestAndPhaseFourTruncation() {
        val digest = SimulationSha256.digest(SimulationFixtures.broadcastIdentityInput.canonicalBytes)
        val expected = digest.copyToByteArray().copyOfRange(0, PHASE_FOUR_PACKET_ID_BYTES)

        assertEquals(PHASE_FOUR_PACKET_ID_BYTES, PacketId.BYTE_SIZE)
        assertEquals(expected.hex(), PacketIdentity.fromSha256(digest).value.hex())
    }

    private fun assertSha256(expected: String, input: ByteArray) {
        assertEquals(expected, SimulationSha256.digest(Bytes.copyOf(input)).hex())
    }

    private companion object {
        const val PHASE_FOUR_PACKET_ID_BYTES: Int = 16
    }
}

internal fun Bytes.hex(): String = copyToByteArray().hex()

internal fun ByteArray.hex(): String =
    joinToString(separator = "") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
