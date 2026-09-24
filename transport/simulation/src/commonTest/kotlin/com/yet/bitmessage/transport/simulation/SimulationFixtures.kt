package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.KnownPacketType
import com.yet.bitmessage.protocol.bitchat.PacketFlags
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.PacketIdentityInput
import com.yet.bitmessage.protocol.bitchat.PacketType
import com.yet.bitmessage.protocol.bitchat.PacketVersion
import com.yet.bitmessage.protocol.bitchat.RawPacket
import com.yet.bitmessage.protocol.bitchat.SigningTranscript
import com.yet.bitmessage.protocol.bitchat.FragmentId
import com.yet.bitmessage.protocol.bitchat.FragmentPayload
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlin.test.assertIs

internal object SimulationFixtures {
    private val sender = WirePeerId.of(
        Bytes.copyOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
    )

    fun messagePacket(
        ttl: UByte = 3u,
        payload: Bytes = Bytes.copyOf(byteArrayOf(0x41, 0x42)),
        timestamp: ULong = 42u,
    ): DecodedPacket {
        val candidate = DecodedPacket(
            version = PacketVersion.of(2u),
            type = PacketType.of(KnownPacketType.MESSAGE.value),
            ttl = ttl,
            timestamp = timestamp,
            flags = PacketFlags.of(0u),
            sender = sender,
            recipient = null,
            route = null,
            payload = payload,
            signature = null,
            rawPacket = RawPacket(Bytes.copyOf(byteArrayOf(0))),
        )
        val encoded = assertIs<EncodeResult.Success>(BitchatCodec.encode(candidate)).bytes
        return assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(encoded)).value
    }

    val broadcastIdentityInput: PacketIdentityInput = PacketIdentity.input(messagePacket())

    /** Generated decode-only signed input; no expected wire or transcript literal is asserted here. */
    fun signedMessageWire(payloadByte: Byte, timestamp: ULong = payloadByte.toUByte().toULong()): Bytes {
        val unsigned = messagePacket(payload = Bytes.copyOf(byteArrayOf(payloadByte)), timestamp = timestamp)
        val wire = unsigned.rawPacket.wireBytes.copyToByteArray()
        val flagsOffset = Byte.SIZE_BYTES * 3 + ULong.SIZE_BYTES
        wire[flagsOffset] = (wire[flagsOffset].toUByte().toUInt() or PacketFlags.SIGNATURE_BIT).toByte()
        val signature = ByteArray(SIGNATURE_BYTES) { index -> (payloadByte.toInt() + index).toByte() }
        val signed = Bytes.copyOf(wire + signature)
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(signed)).value
        assertIs<DecodeResult.Success<Bytes>>(SigningTranscript.build(decoded))
        return signed
    }

    private const val SIGNATURE_BYTES = 64

    /** Wraps a known decode-only type using a production-encoded v2 header of the same payload length. */
    fun decodeOnlyOuterPacket(
        type: PacketType,
        payload: Bytes,
        ttl: UByte = 0u,
        timestamp: ULong = 42u,
    ): Bytes {
        val emitted = messagePacket(ttl = ttl, payload = payload, timestamp = timestamp)
        val wire = emitted.rawPacket.wireBytes.copyToByteArray()
        wire[Byte.SIZE_BYTES] = type.value.toByte()
        val candidate = Bytes.copyOf(wire)
        val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(candidate)).value
        require(decoded.type == type && decoded.payload == payload)
        return candidate
    }

    fun fragmentOuterWires(innerWire: Bytes, fragmentBytes: Int, streamOrdinal: Int = 1): List<Bytes> {
        require(fragmentBytes > 0 && streamOrdinal >= 0)
        val inner = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(innerWire)).value
        val raw = innerWire.copyToByteArray()
        val total = (raw.size + fragmentBytes - 1) / fragmentBytes
        require(total in 1..UShort.MAX_VALUE.toInt())
        val id = FragmentId.of(Bytes.copyOf(ByteArray(FragmentId.BYTE_SIZE) { index ->
            (streamOrdinal + index).toByte()
        }))
        return (0 until total).map { index ->
            val begin = index * fragmentBytes
            val end = minOf(raw.size, begin + fragmentBytes)
            val payload = assertIs<EncodeResult.Success>(FragmentPayloadCodec.encode(FragmentPayload(
                id = id,
                index = index.toUShort(),
                total = total.toUShort(),
                originalType = inner.type,
                data = Bytes.copyOf(raw.copyOfRange(begin, end)),
            ))).bytes
            decodeOnlyOuterPacket(PacketType.of(KnownPacketType.FRAGMENT.value), payload,
                timestamp = (100 + index).toULong())
        }
    }
}
