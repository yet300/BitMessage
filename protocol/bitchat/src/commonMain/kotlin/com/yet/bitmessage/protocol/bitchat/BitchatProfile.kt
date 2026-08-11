package com.yet.bitmessage.protocol.bitchat

object BitchatBaseline2026_08 {
    val supportedVersions: Set<PacketVersion> = setOf(
        PacketVersion.of(1u),
        PacketVersion.of(2u),
    )

    val decodeLimits: DecodeLimits = DecodeLimits(
        maxPayloadBytes = 16 * 1024 * 1024,
        maxRouteEntries = 32,
    )

    fun canDecode(version: PacketVersion): Boolean = version in supportedVersions

    fun canEmit(type: PacketType): Boolean = type.knownType == KnownPacketType.MESSAGE

    fun canRelay(type: PacketType): Boolean = type.knownType == KnownPacketType.MESSAGE
}

enum class PacketAdmissionPolicy {
    VERIFY_SIGNATURE,
    ALLOW_UNSIGNED_MESSAGE,
    ALLOW_UNSIGNED_FRAGMENT,
    REJECT,
}

fun BitchatBaseline2026_08.admissionPolicy(packet: DecodedPacket): PacketAdmissionPolicy =
    when (packet.type.knownType) {
        KnownPacketType.MESSAGE ->
            if (packet.signature == null) PacketAdmissionPolicy.ALLOW_UNSIGNED_MESSAGE
            else PacketAdmissionPolicy.VERIFY_SIGNATURE
        KnownPacketType.FRAGMENT ->
            if (packet.signature == null) PacketAdmissionPolicy.ALLOW_UNSIGNED_FRAGMENT
            else PacketAdmissionPolicy.VERIFY_SIGNATURE
        null -> PacketAdmissionPolicy.REJECT
    }
