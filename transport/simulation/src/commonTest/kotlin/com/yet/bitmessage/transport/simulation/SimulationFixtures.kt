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
}
