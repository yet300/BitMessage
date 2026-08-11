package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.model.PeerId
import kotlin.jvm.JvmInline

@JvmInline
value class PacketVersion private constructor(
    val value: UByte,
) {
    companion object {
        fun of(value: UByte): PacketVersion = PacketVersion(value)
    }
}

@JvmInline
value class PacketType private constructor(
    val value: UByte,
) {
    val knownType: KnownPacketType?
        get() = KnownPacketType.entries.firstOrNull { it.value == value }

    companion object {
        fun of(value: UByte): PacketType = PacketType(value)
    }
}

enum class KnownPacketType(
    val value: UByte,
) {
    MESSAGE(0x02u),
    FRAGMENT(0x20u),
}

@JvmInline
value class PacketFlags private constructor(
    val value: UByte,
) {
    val hasRecipient: Boolean
        get() = hasBit(RECIPIENT_BIT)

    val hasSignature: Boolean
        get() = hasBit(SIGNATURE_BIT)

    val isCompressed: Boolean
        get() = hasBit(COMPRESSED_BIT)

    val hasRoute: Boolean
        get() = hasBit(ROUTE_BIT)

    val hasPadding: Boolean
        get() = hasBit(PADDING_BIT)

    private fun hasBit(bit: UInt): Boolean = (value.toUInt() and bit) != 0u

    companion object {
        const val RECIPIENT_BIT: UInt = 0x01u
        const val SIGNATURE_BIT: UInt = 0x02u
        const val COMPRESSED_BIT: UInt = 0x04u
        const val ROUTE_BIT: UInt = 0x08u
        const val PADDING_BIT: UInt = 0x10u

        fun of(value: UByte): PacketFlags = PacketFlags(value)
    }
}

@JvmInline
value class WirePeerId private constructor(
    val value: PeerId,
) {
    companion object {
        const val BYTE_SIZE: Int = 8

        fun of(bytes: Bytes): WirePeerId =
            WirePeerId(PeerId.of(Bytes.requireExactSize(bytes.copyToByteArray(), BYTE_SIZE)))
    }
}

data class RawPacket(
    val wireBytes: Bytes,
)

class WireRoute(entries: List<WirePeerId>) {
    private val storedEntries = entries.toList()

    val entries: List<WirePeerId>
        get() = storedEntries.toList()

    override fun equals(other: Any?): Boolean =
        other is WireRoute && storedEntries == other.storedEntries

    override fun hashCode(): Int = storedEntries.hashCode()

    override fun toString(): String = "WireRoute(entries=${storedEntries.size})"
}

data class DecodedPacket(
    val version: PacketVersion,
    val type: PacketType,
    val ttl: UByte,
    val timestamp: ULong,
    val flags: PacketFlags,
    val sender: WirePeerId,
    val recipient: WirePeerId?,
    val route: WireRoute?,
    val payload: Bytes,
    val signature: Bytes?,
    val rawPacket: RawPacket,
    val compressionEnvelope: CompressionEnvelope? = null,
)

data class DecodeLimits(
    val maxPayloadBytes: Int,
    val maxRouteEntries: Int,
) {
    init {
        require(maxPayloadBytes >= 0) { "Maximum payload bytes must not be negative." }
        require(maxRouteEntries >= 0) { "Maximum route entries must not be negative." }
    }
}
