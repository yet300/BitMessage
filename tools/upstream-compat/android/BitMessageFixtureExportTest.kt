package com.bitchat.android.protocol

import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.UnknownAnnouncementTLV
import org.junit.Test

class BitMessageFixtureExportTest {
    @Test
    fun exportDeterministicFixtures() {
        val sender = hex("0011223344556677")
        val recipient = hex("8899aabbccddeeff")
        val timestamp = 0x0102_0304_0506_0708uL
        val payload = byteArrayOf(0x41, 0x42)
        listOf(
            "android-v1-broadcast" to packet(1u, sender, null, timestamp, payload),
            "android-v1-recipient" to packet(1u, sender, recipient, timestamp, payload),
            "android-v2-broadcast" to packet(2u, sender, null, timestamp, payload),
            "android-v2-recipient" to packet(2u, sender, recipient, timestamp, payload),
            "android-v2-route" to packet(2u, sender, recipient, timestamp, payload, listOf(sender, recipient)),
        ).forEach { (id, value) -> emit(id, BinaryProtocol.encode(value, padding = false)!!) }

        val legacy = IdentityAnnouncement(
            nickname = "peer",
            noisePublicKey = ByteArray(32) { 0x11 },
            signingPublicKey = ByteArray(32) { 0x22 },
        )
        val extended = legacy.copy(
            capabilities = PeerCapabilities(0x8100),
            unknownTLVs = listOf(UnknownAnnouncementTLV(0x04, hex("0102030405060708"))),
        )
        emit("android-announce-legacy", legacy.encode()!!)
        emit("android-announce-extended", extended.encode()!!)
    }

    private fun packet(version: UByte, sender: ByteArray, recipient: ByteArray?, timestamp: ULong, payload: ByteArray, route: List<ByteArray>? = null) =
        BitchatPacket(version = version, type = MessageType.MESSAGE.value, senderID = sender, recipientID = recipient, timestamp = timestamp, payload = payload, ttl = 3u, route = route)

    private fun emit(id: String, bytes: ByteArray) = println("BITMESSAGE_FIXTURE $id ${bytes.joinToString("") { "%02x".format(it) }}")
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
