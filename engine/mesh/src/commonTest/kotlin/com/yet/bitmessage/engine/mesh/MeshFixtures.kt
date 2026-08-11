package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.FragmentId
import com.yet.bitmessage.protocol.bitchat.FragmentPayload
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.KnownPacketType
import com.yet.bitmessage.protocol.bitchat.PacketFlags
import com.yet.bitmessage.protocol.bitchat.PacketType
import com.yet.bitmessage.protocol.bitchat.RawPacket
import com.yet.bitmessage.protocol.bitchat.SigningTranscript
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlin.test.assertIs

internal object MeshFixtures {
    val now: MonotonicTime = MonotonicTime.ZERO
    val generation: Generation = Generation(3)
    val localPeer: WirePeerId = WirePeerId.of(bytes("0011223344556677"))
    val linkA: LinkId = LinkId.of("link-a")
    val linkB: LinkId = LinkId.of("link-b")
    val broadcastPacket: DecodedPacket = assertIs<DecodeResult.Success<DecodedPacket>>(
        BitchatCodec.decode(bytes("0202030102030405060708000000000200112233445566774142")),
    ).value
    val signedPacket: DecodedPacket = signedPacketWithPayload(broadcastPacket.payload)
    val fakeSha256Digest: Bytes = Bytes.copyOf(ByteArray(32) { index -> index.toByte() })
    val fragmentId: FragmentId = FragmentId.of(Bytes.copyOf(ByteArray(FragmentId.BYTE_SIZE) { (it + 9).toByte() }))
    val fragmentZero: FragmentPayload = fragment(index = 0, range = 0 until 13)
    val fragmentOne: FragmentPayload = fragment(index = 1, range = 13 until 26)
    val fragmentZeroBytes: Bytes = encodeFragment(fragmentZero)
    val fragmentOneBytes: Bytes = encodeFragment(fragmentOne)
    val fragmentZeroPacket: DecodedPacket = fragmentPacket(fragmentZeroBytes)
    val fragmentOnePacket: DecodedPacket = fragmentPacket(fragmentOneBytes)

    fun state(
        lifecycle: MeshLifecycle = MeshLifecycle.RUNNING,
    ): MeshState = MeshState(
        generation = generation,
        localPeer = localPeer,
        observedAt = now,
        lifecycle = lifecycle,
    )

    fun packetDecoded(
        packet: DecodedPacket = broadcastPacket,
        source: PacketSource = PacketSource.Link(linkA),
        eventGeneration: Generation = generation,
        observedAt: MonotonicTime = now,
    ): MeshEvent.PacketDecoded {
        val transcript = if (packet.signature == null) {
            null
        } else {
            assertIs<DecodeResult.Success<Bytes>>(SigningTranscript.build(packet)).value
        }
        return MeshEvent.PacketDecoded(
            generation = eventGeneration,
            observedAt = observedAt,
            source = source,
            packet = packet,
            signingTranscript = transcript,
        )
    }

    fun signedPacketWithPayload(payload: Bytes): DecodedPacket {
        val signature = Bytes.copyOf(ByteArray(64) { 0x5a.toByte() })
        val candidate = broadcastPacket.copy(
            ttl = 7u,
            flags = PacketFlags.of(0u),
            payload = payload,
            signature = null,
        )
        val unsignedWire = assertIs<EncodeResult.Success>(BitchatCodec.encode(candidate)).bytes.copyToByteArray()
        unsignedWire[V2_FLAGS_OFFSET] = PacketFlags.SIGNATURE_BIT.toByte()
        val wire = Bytes.copyOf(unsignedWire + signature.copyToByteArray())
        return assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value
    }

    private fun fragment(index: Int, range: IntRange): FragmentPayload = FragmentPayload(
        id = fragmentId,
        index = index.toUShort(),
        total = 2u,
        originalType = broadcastPacket.type,
        data = Bytes.copyOf(broadcastPacket.rawPacket.wireBytes.copyToByteArray().sliceArray(range)),
    )

    private fun encodeFragment(fragment: FragmentPayload): Bytes =
        assertIs<EncodeResult.Success>(FragmentPayloadCodec.encode(fragment)).bytes

    private fun fragmentPacket(payload: Bytes): DecodedPacket = broadcastPacket.copy(
        type = PacketType.of(KnownPacketType.FRAGMENT.value),
        payload = payload,
        rawPacket = RawPacket(payload),
    )

    fun bytes(hex: String): Bytes =
        Bytes.copyOf(
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            },
        )

    private const val V2_FLAGS_OFFSET: Int = 11
}
