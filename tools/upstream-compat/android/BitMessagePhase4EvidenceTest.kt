package com.bitchat.android.protocol

import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.sync.PacketIdUtil
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BitMessagePhase4EvidenceTest {
    @Test
    fun reproducePacketIdentitySigningRelayAndFragments() {
        val sender = hex("0011223344556677")
        val timestamp = 0x0102_0304_0506_0708uL
        val payload = hex("4142")
        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = sender,
            recipientID = null,
            timestamp = timestamp,
            payload = payload,
            signature = null,
            ttl = 3u,
        )
        val identityInput = byteArrayOf(packet.type.toByte()) + packet.senderID +
            ByteBuffer.allocate(ULong.SIZE_BYTES).putLong(timestamp.toLong()).array() + packet.payload
        val digest = MessageDigest.getInstance("SHA-256").digest(identityInput)
        val packetID = PacketIdUtil.computeIdBytes(packet)

        val signature = ByteArray(64) { 0x5a }
        val signed = packet.copy(signature = signature, ttl = 7u)
        val signingTranscript = requireNotNull(signed.toBinaryDataForSigning())
        val relayed = signed.copy(ttl = 6u)
        val relayedBytes = requireNotNull(BinaryProtocol.encode(relayed, padding = false))
        val decodedRelay = requireNotNull(BinaryProtocol.decode(relayedBytes))
        val relayedTranscript = requireNotNull(decodedRelay.toBinaryDataForSigning())

        val inner = hex("0202030102030405060708000000000200112233445566774142")
        val fragmentID = hex("0001020304050607")
        val first = FragmentPayload(fragmentID, 0, 2, MessageType.MESSAGE.value, inner.copyOfRange(0, 13))
        val second = FragmentPayload(fragmentID, 1, 2, MessageType.MESSAGE.value, inner.copyOfRange(13, inner.size))
        val firstBytes = first.encode()
        val secondBytes = second.encode()
        val decodedFirst = requireNotNull(FragmentPayload.decode(firstBytes))
        val decodedSecond = requireNotNull(FragmentPayload.decode(secondBytes))
        val reassembled = decodedFirst.data + decodedSecond.data

        emit("identity-input", identityInput)
        emit("sha256", digest)
        emit("packet-id", packetID)
        emit("signing-transcript", signingTranscript)
        emit("fragment-metadata", FragmentPayload(fragmentID, 1, 2, MessageType.MESSAGE.value, hex("aabb")).encode())
        emit("fragment-zero", firstBytes)
        emit("fragment-one", secondBytes)
        emit("reassembled", reassembled)

        assertEquals("02001122334455667701020304050607084142", identityInput.hex())
        assertEquals("25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda", digest.hex())
        assertEquals("25429fbd15e2051049307f8e650ae863", packetID.hex())
        val signingCore = hex("0202000102030405060708000000000200112233445566774142")
        assertEquals(256, signingTranscript.size)
        assertArrayEquals(signingCore, signingTranscript.copyOfRange(0, signingCore.size))
        assertArrayEquals(ByteArray(230) { 0xe6.toByte() }, signingTranscript.copyOfRange(signingCore.size, signingTranscript.size))
        assertArrayEquals(signature, decodedRelay.signature)
        assertEquals(6, relayedBytes[2].toInt())
        assertArrayEquals(signingTranscript, relayedTranscript)
        assertEquals("0001020304050607000000020202020301020304050607080000", firstBytes.hex())
        assertEquals("0001020304050607000100020200000200112233445566774142", secondBytes.hex())
        assertArrayEquals(inner, reassembled)
    }

    private fun emit(id: String, bytes: ByteArray) = println("BITMESSAGE_PHASE4 $id ${bytes.hex()}")
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
