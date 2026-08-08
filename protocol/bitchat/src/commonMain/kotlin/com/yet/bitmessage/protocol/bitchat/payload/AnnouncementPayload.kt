package com.yet.bitmessage.protocol.bitchat.payload

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.WirePeerId

data class AnnouncementTlv(
    val type: UByte,
    val value: Bytes,
)

class CapabilityBits private constructor(
    val rawValue: Bytes,
    val low64: ULong,
) {
    companion object {
        fun fromWire(value: Bytes): CapabilityBits {
            require(value.size in 1..ULong_BYTES) { "Capability TLV must contain one to eight bytes." }
            var low64 = 0uL
            repeat(value.size) { index ->
                low64 = low64 or (value[index].toULong() shl (index * BITS_PER_BYTE))
            }
            return CapabilityBits(value, low64)
        }

        private const val ULong_BYTES = 8
        private const val BITS_PER_BYTE = 8
    }
}

class AnnouncementPayload internal constructor(
    val nickname: Bytes,
    val noisePublicKey: Bytes,
    val signingPublicKey: Bytes,
    directNeighbors: List<WirePeerId>,
    val capabilities: CapabilityBits?,
    tlvs: List<AnnouncementTlv>,
    val rawPayload: Bytes,
) {
    private val storedDirectNeighbors = directNeighbors.toList()
    private val storedTlvs = tlvs.toList()

    val directNeighbors: List<WirePeerId>
        get() = storedDirectNeighbors.toList()

    val tlvs: List<AnnouncementTlv>
        get() = storedTlvs.toList()

    val unknownTlvs: List<AnnouncementTlv>
        get() = storedTlvs.filter { it.type !in KNOWN_TLV_TYPES }

    private companion object {
        val KNOWN_TLV_TYPES = setOf<UByte>(0x01u, 0x02u, 0x03u, 0x04u, 0x05u)
    }
}
