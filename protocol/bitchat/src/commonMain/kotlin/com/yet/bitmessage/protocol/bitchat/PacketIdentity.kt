package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.jvm.JvmInline

@JvmInline
value class PacketId private constructor(
    val value: Bytes,
) {
    companion object {
        const val BYTE_SIZE: Int = 16

        fun of(value: Bytes): PacketId =
            PacketId(Bytes.requireExactSize(value.copyToByteArray(), BYTE_SIZE))
    }
}

data class PacketIdentityInput(
    val canonicalBytes: Bytes,
)

object PacketIdentity {
    fun input(packet: DecodedPacket): PacketIdentityInput {
        val sender = packet.sender.value.value.copyToByteArray()
        val payload = packet.payload.copyToByteArray()
        val canonical = ByteArray(1 + sender.size + ULong.SIZE_BYTES + payload.size)
        canonical[0] = packet.type.value.toByte()
        sender.copyInto(canonical, destinationOffset = 1)
        repeat(ULong.SIZE_BYTES) { index ->
            val shift = (ULong.SIZE_BYTES - index - 1) * Byte.SIZE_BITS
            canonical[1 + sender.size + index] = (packet.timestamp shr shift).toByte()
        }
        payload.copyInto(canonical, destinationOffset = 1 + sender.size + ULong.SIZE_BYTES)
        return PacketIdentityInput(Bytes.copyOf(canonical))
    }

    fun fromSha256(digest: Bytes): PacketId {
        val exactDigest = Bytes.requireExactSize(digest.copyToByteArray(), SHA256_BYTES).copyToByteArray()
        return PacketId.of(Bytes.copyOf(exactDigest.copyOfRange(0, PacketId.BYTE_SIZE)))
    }

    private const val SHA256_BYTES: Int = 32
}
