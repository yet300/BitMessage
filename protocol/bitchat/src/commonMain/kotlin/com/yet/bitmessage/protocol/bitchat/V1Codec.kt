package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.internal.BinaryReadResult
import com.yet.bitmessage.protocol.bitchat.internal.BinaryReader
import com.yet.bitmessage.protocol.bitchat.internal.BinaryWriteResult
import com.yet.bitmessage.protocol.bitchat.internal.BinaryWriter

internal object V1Codec {
    fun decode(wireBytes: Bytes): DecodeResult<DecodedPacket> {
        val reader = BinaryReader(wireBytes)
        val version = reader.readUByte().decodeOrNull() ?: return truncated()
        val type = PacketType.of(reader.readUByte().decodeOrNull() ?: return truncated())
        val ttl = reader.readUByte().decodeOrNull() ?: return truncated()
        val timestamp = reader.readULongBigEndian().decodeOrNull() ?: return truncated()
        val flags = PacketFlags.of(reader.readUByte().decodeOrNull() ?: return truncated())
        val payloadLength = reader.readUShortBigEndian().decodeOrNull()?.toInt() ?: return truncated()

        if (PacketVersion.of(version) != PacketVersion.of(1u)) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION)
        }
        if (type.knownType == null) return DecodeResult.Failure(DecodeError.PROFILE_VIOLATION)
        if (payloadLength > BitchatBaseline2026_08.decodeLimits.maxPayloadBytes) {
            return DecodeResult.Failure(DecodeError.LIMIT_EXCEEDED)
        }

        val sender = reader.readExact(WirePeerId.BYTE_SIZE).decodeOrNull()?.let(WirePeerId::of) ?: return truncated()
        val recipient =
            if (flags.hasRecipient) {
                reader.readExact(WirePeerId.BYTE_SIZE).decodeOrNull()?.let(WirePeerId::of) ?: return truncated()
            } else {
                null
            }

        if (flags.hasRoute || flags.hasPadding) {
            return DecodeResult.Failure(DecodeError.UNSUPPORTED_FEATURE)
        }

        val payload = reader.readExact(payloadLength).decodeOrNull()
            ?: return DecodeResult.Failure(DecodeError.INVALID_LENGTH)
        val signature =
            if (flags.hasSignature) {
                reader.readExact(SIGNATURE_BYTES).decodeOrNull() ?: return truncated()
            } else {
                null
            }
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
                route = null,
                payload = payload,
                signature = signature,
                rawPacket = RawPacket(wireBytes),
                compressionEnvelope = if (flags.isCompressed) CompressionEnvelope(payload) else null,
            ),
        )
    }

    fun encode(packet: DecodedPacket, requireEmittable: Boolean = true): EncodeResult {
        if (packet.type.knownType == null ||
            (requireEmittable && !BitchatBaseline2026_08.canEmit(packet.type))
        ) {
            return EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
        if (packet.route != null || packet.signature != null) {
            return EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE)
        }
        if (packet.payload.size > UShort.MAX_VALUE.toInt()) {
            return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        }
        if (packet.flags.hasRecipient != (packet.recipient != null)) {
            return EncodeResult.Failure(EncodeError.PROFILE_VIOLATION)
        }
        if (packet.flags.hasSignature || packet.flags.isCompressed || packet.flags.hasRoute || packet.flags.hasPadding) {
            return EncodeResult.Failure(EncodeError.UNSUPPORTED_FEATURE)
        }

        val writer = BinaryWriter(
            maxSize = V1_HEADER_AND_SENDER_BYTES + packet.payload.size + if (packet.recipient == null) 0 else WirePeerId.BYTE_SIZE,
        )
        if (!writer.writeUByte(packet.version.value).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (!writer.writeUByte(packet.type.value).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (!writer.writeUByte(packet.ttl).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (!writer.writeULongBigEndian(packet.timestamp).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (!writer.writeUByte(packet.flags.value).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (!writer.writeUShortBigEndian(packet.payload.size.toUShort()).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (!writer.writeExact(packet.sender.value.value).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        if (packet.recipient != null && !writer.writeExact(packet.recipient.value.value).succeeded()) {
            return EncodeResult.Failure(EncodeError.INVALID_LENGTH)
        }
        if (!writer.writeExact(packet.payload).succeeded()) return EncodeResult.Failure(EncodeError.INVALID_LENGTH)

        return EncodeResult.Success(writer.toBytes())
    }

    private fun <T> BinaryReadResult<T>.decodeOrNull(): T? =
        (this as? BinaryReadResult.Success<T>)?.value

    private fun BinaryWriteResult.succeeded(): Boolean = this == BinaryWriteResult.Success

    private fun truncated(): DecodeResult.Failure = DecodeResult.Failure(DecodeError.TRUNCATED)

    private const val V1_HEADER_AND_SENDER_BYTES = 22
    private const val SIGNATURE_BYTES = 64
}
