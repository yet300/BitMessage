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
}
