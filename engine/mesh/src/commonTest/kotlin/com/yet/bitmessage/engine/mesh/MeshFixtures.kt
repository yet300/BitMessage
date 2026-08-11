package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
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

    fun state(
        lifecycle: MeshLifecycle = MeshLifecycle.RUNNING,
    ): MeshState = MeshState(
        generation = generation,
        localPeer = localPeer,
        observedAt = now,
        lifecycle = lifecycle,
    )

    fun bytes(hex: String): Bytes =
        Bytes.copyOf(
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            },
        )
}
