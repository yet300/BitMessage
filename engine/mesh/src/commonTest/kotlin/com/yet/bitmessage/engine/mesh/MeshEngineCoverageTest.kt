package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeshEngineCoverageTest {
    @Test
    fun signedAdmissionTraversesTheCompleteReducerToOneResolvedWrite() {
        val engine = MeshEngine()
        var state = MeshFixtures.state()
        listOf(MeshFixtures.linkA, MeshFixtures.linkB).forEach { linkId ->
            state = engine.reduce(
                state,
                MeshEvent.LinkObserved(
                    generation = MeshFixtures.generation,
                    observedAt = MeshFixtures.now,
                    event = LinkEvent.Opened(linkId, LinkCapabilities(4096, true)),
                ),
            ).state
        }

        val received = engine.reduce(
            state,
            MeshEvent.LinkObserved(
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    signedPacket.rawPacket.wireBytes,
                ),
            ),
        )
        val decode = received.effects.filterIsInstance<MeshEffect.DecodePacket>().single()
        val decoded = engine.reduce(
            received.state,
            MeshEvent.PacketDecoded(
                correlationId = decode.correlationId,
                generation = decode.generation,
                observedAt = MeshFixtures.now,
                source = decode.source,
                result = DecodeResult.Success(signedPacket),
            ),
        )
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val digested = engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = digest.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(sha256Digest),
            ),
        )
        val verify = digested.effects.filterIsInstance<MeshEffect.VerifySignature>().single()
        val admitted = engine.reduce(
            digested.state,
            MeshEvent.SignatureVerified(
                correlationId = verify.correlationId,
                generation = verify.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(true),
            ),
        )
        assertEquals(1, admitted.state.admittedPackets.size)
        assertTrue(admitted.effects.any { it is MeshEffect.PublishPublicPayload })

        val entropy = admitted.effects.filterIsInstance<MeshEffect.RequestEntropy>().single()
        val scheduled = engine.reduce(
            admitted.state,
            MeshEvent.EntropyProvided(
                correlationId = entropy.correlationId,
                generation = entropy.generation,
                observedAt = MeshFixtures.now,
                packetId = entropy.packetId,
                source = entropy.source,
                packet = entropy.packet,
                outgoingTtl = entropy.outgoingTtl,
                result = MeshResult.Success(bytes("0000")),
            ),
        )
        val schedule = scheduled.effects.filterIsInstance<MeshEffect.Schedule>().single()
        val timer = engine.reduce(
            scheduled.state,
            MeshEvent.TimerElapsed(
                correlationId = schedule.correlationId,
                generation = schedule.generation,
                observedAt = MeshFixtures.now,
                timerId = schedule.timerId,
            ),
        )
        val encode = timer.effects.filterIsInstance<MeshEffect.EncodeRelay>().single()
        val encoded = RelayEncoding.withTtl(encode.packet, encode.outgoingTtl)
        val relayed = engine.reduce(
            timer.state,
            MeshEvent.RelayEncoded(
                correlationId = encode.correlationId,
                generation = encode.generation,
                observedAt = MeshFixtures.now,
                packetId = encode.packetId,
                targets = encode.targets,
                result = encoded,
            ),
        )
        val write = relayed.effects.filterIsInstance<MeshEffect.WriteLink>().single()
        val completed = engine.reduce(
            relayed.state,
            MeshEvent.LinkCompleted(
                correlationId = write.correlationId,
                generation = write.generation,
                observedAt = MeshFixtures.now,
                result = LinkResult.Written(
                    write.command.linkId,
                    write.command.correlationId,
                    write.command.generation,
                ),
            ),
        )

        assertTrue(completed.effects.isEmpty())
        assertTrue(completed.state.pendingLinkWrites.isEmpty())
        val relayedPacket = assertIs<DecodeResult.Success<DecodedPacket>>(
            BitchatCodec.decode(write.command.bytes),
        ).value
        assertEquals(6u.toUByte(), relayedPacket.ttl)
        assertEquals(signedPacket.signature, relayedPacket.signature)
    }

    private companion object {
        val signedPacket: DecodedPacket = assertIs<DecodeResult.Success<DecodedPacket>>(
            BitchatCodec.decode(
                Bytes.copyOf(
                    bytes("0202070102030405060708020000000200112233445566774142").copyToByteArray() +
                        ByteArray(64) { 0x5a },
                ),
            ),
        ).value
        val sha256Digest: Bytes =
            bytes("25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda")

        fun bytes(hex: String): Bytes = MeshFixtures.bytes(hex)
    }
}
