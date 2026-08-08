package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.internal.BinaryReadResult
import com.yet.bitmessage.protocol.bitchat.internal.BinaryReader
import com.yet.bitmessage.protocol.bitchat.internal.BinaryWriteResult
import com.yet.bitmessage.protocol.bitchat.internal.BinaryWriter

internal object V2Codec {
    fun decode(wireBytes: Bytes): DecodeResult<DecodedPacket> {
        val reader = BinaryReader(wireBytes)
        val version = reader.readUByte().decodeOrNull() ?: return truncated()
        val type = PacketType.of(reader.readUByte().decodeOrNull() ?: return truncated())
        val ttl = reader.readUByte().decodeOrNull() ?: return truncated()
        val timestamp = reader.readULongBigEndian().decodeOrNull() ?: return truncated()
        val flags = PacketFlags.of(reader.readUByte().decodeOrNull() ?: return truncated())
        val advertisedPayloadLength = reader.readUIntBigEndian().decodeOrNull() ?: return truncated()

        if (PacketVersion.of(version) != PacketVersion.of(2u)) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION)
        }
        if (type.knownType == null) return DecodeResult.Failure(DecodeError.PROFILE_VIOLATION)
        if (advertisedPayloadLength > BitchatBaseline2026_08.decodeLimits.maxPayloadBytes.toUInt()) {
            return DecodeResult.Failure(DecodeError.LIMIT_EXCEEDED)
        }

        val sender = reader.readExact(WirePeerId.BYTE_SIZE).decodeOrNull()?.let(WirePeerId::of) ?: return truncated()
        val recipient =
            if (flags.hasRecipient) {
                reader.readExact(WirePeerId.BYTE_SIZE).decodeOrNull()?.let(WirePeerId::of) ?: return truncated()
            } else {
                null
            }

        if (flags.hasSignature || flags.isCompressed || flags.hasPadding) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE)
        }

        val route =
            if (flags.hasRoute) {
                val routeEntryCount = reader.readUByte().decodeOrNull()?.toInt() ?: return truncated()
                if (routeEntryCount > BitchatBaseline2026_08.decodeLimits.maxRouteEntries) {
                    return DecodeResult.Failure(DecodeError.LIMIT_EXCEEDED)
                }
                val entries = ArrayList<WirePeerId>(routeEntryCount)
                repeat(routeEntryCount) {
                    val entry = reader.readExact(WirePeerId.BYTE_SIZE).decodeOrNull()?.let(WirePeerId::of) ?: return truncated()
                    entries += entry
                }
                WireRoute(entries)
            } else {
                null
            }

        val payload = reader.readExact(advertisedPayloadLength.toInt()).decodeOrNull()
            ?: return DecodeResult.Failure(DecodeError.INVALID_LENGTH)
        if (reader.remaining != 0) return DecodeResult.Failure(DecodeError.INVALID_LENGTH)

        return DecodeResult.Success(
            DecodedPacket(
                version = PacketVersion.of(version),
                type = type,
                ttl = ttl,
                timestamp = timestamp,
                flags = flags,
                sender = sender,
                recipient = recipient,
                route = route,
                payload = payload,
                signature = null,
                rawPacket = RawPacket(wireBytes),
            ),
        )
    }

    fun encode(packet: DecodedPacket): EncodeResult {
        if (packet.type.knownType == null || !BitchatBaseline2026_08.canEmit(packet.type)) {
            return EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
        if (packet.signature != null) return EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE)
        if (packet.payload.size > BitchatBaseline2026_08.decodeLimits.maxPayloadBytes) {
            return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        }
        if ((packet.route?.entries?.size
                ?: 0) > BitchatBaseline2026_08.decodeLimits.maxRouteEntries
        ) {
            return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        }
        if (packet.flags.hasRecipient != (packet.recipient != null) || packet.flags.hasRoute != (packet.route != null)) {
            return EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
        if (packet.flags.hasSignature || packet.flags.isCompressed || packet.flags.hasPadding) {
            return EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE)
        }

        val routeSize = packet.route?.entries?.size ?: 0
        val writer = BinaryWriter(
            maxSize = V2_HEADER_AND_SENDER_BYTES + packet.payload.size +
                if (packet.recipient == null) 0 else WirePeerId.BYTE_SIZE +
                if (packet.route == null) 0 else ROUTE_COUNT_BYTES + (routeSize * WirePeerId.BYTE_SIZE),
        )
        if (!writer.writeUByte(packet.version.value).succeeded()) return failedWrite()
        if (!writer.writeUByte(packet.type.value).succeeded()) return failedWrite()
        if (!writer.writeUByte(packet.ttl).succeeded()) return failedWrite()
        if (!writer.writeULongBigEndian(packet.timestamp).succeeded()) return failedWrite()
        if (!writer.writeUByte(packet.flags.value).succeeded()) return failedWrite()
        if (!writer.writeUIntBigEndian(packet.payload.size.toUInt()).succeeded()) return failedWrite()
        if (!writer.writeExact(packet.sender.value.value).succeeded()) return failedWrite()
        if (packet.recipient != null && !writer.writeExact(packet.recipient.value.value).succeeded()) return failedWrite()
        if (packet.route != null) {
            if (!writer.writeUByte(routeSize.toUByte()).succeeded()) return failedWrite()
            packet.route.entries.forEach { entry ->
                if (!writer.writeExact(entry.value.value).succeeded()) return failedWrite()
            }
        }
        if (!writer.writeExact(packet.payload).succeeded()) return failedWrite()

        return EncodeResult.Success(writer.toBytes())
    }

    private fun <T> BinaryReadResult<T>.decodeOrNull(): T? =
        (this as? BinaryReadResult.Success<T>)?.value

    private fun BinaryWriteResult.succeeded(): Boolean = this == BinaryWriteResult.Success

    private fun truncated(): DecodeResult.Failure = DecodeResult.Failure(DecodeError.TRUNCATED)

    private fun failedWrite(): EncodeResult.Failure = EncodeResult.Failure(EncodeError.INVALID_LENGTH)

    private const val V2_HEADER_AND_SENDER_BYTES = 24
    private const val ROUTE_COUNT_BYTES = 1
}
