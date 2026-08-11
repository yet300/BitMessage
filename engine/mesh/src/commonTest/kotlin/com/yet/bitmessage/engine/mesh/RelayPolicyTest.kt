package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class RelayPolicyTest {
    @Test
    fun ttlBoundariesSeparateLocalDispatchFromRelay() {
        assertEquals(null, RelayPolicy.outgoingTtl(0u))
        assertEquals(null, RelayPolicy.outgoingTtl(1u))
        assertEquals(1u.toUByte(), RelayPolicy.outgoingTtl(2u))
        assertEquals(6u.toUByte(), RelayPolicy.outgoingTtl(7u))
        assertEquals(6u.toUByte(), RelayPolicy.outgoingTtl(255u))
    }

    @Test
    fun broadcastAndLocalRecipientDispatchWhileNonlocalRecipientDoesNot() {
        val engine = MeshEngine()
        val broadcast = admitUnsigned(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val local = admitUnsigned(engine, readyState(engine), localRecipientPacket)
        val nonlocal = admitUnsigned(engine, readyState(engine), nonlocalRecipientPacket)

        assertEquals(1, broadcast.effects.count { it is MeshEffect.PublishPublicPayload })
        assertEquals(1, local.effects.count { it is MeshEffect.PublishPublicPayload })
        assertTrue(nonlocal.effects.none { it is MeshEffect.PublishPublicPayload })
    }

    @Test
    fun fragmentAdmissionDecodesMetadataWithoutPublicDispatchOrOuterRelay() {
        val engine = MeshEngine()
        val admitted = admitUnsigned(engine, readyState(engine), fragmentPacket)

        val fragment = assertIs<MeshEffect.DecodeFragmentPayload>(
            admitted.effects.single { it is MeshEffect.DecodeFragmentPayload },
        )
        assertEquals(fragmentPacket.payload, fragment.payload)
        assertTrue(admitted.effects.none { it is MeshEffect.PublishPublicPayload })
        assertTrue(admitted.effects.none { it is MeshEffect.RequestEntropy })
    }

    @Test
    fun localDispatchStillOccursAtTtlZeroAndOneWhileRelayStartsAtTwo() {
        val engine = MeshEngine()
        val ttlZero = admitUnsigned(engine, readyState(engine), packetWithTtl(0u))
        val ttlOne = admitUnsigned(engine, readyState(engine), packetWithTtl(1u))
        val ttlTwo = admitUnsigned(engine, readyState(engine), packetWithTtl(2u))
        val ttlSeven = admitUnsigned(engine, readyState(engine), packetWithTtl(7u))
        val hostile = admitUnsigned(engine, readyState(engine), packetWithTtl(255u))

        listOf(ttlZero, ttlOne, ttlTwo, ttlSeven, hostile).forEach { transition ->
            assertEquals(1, transition.effects.count { it is MeshEffect.PublishPublicPayload })
        }
        assertTrue(ttlZero.effects.none { it is MeshEffect.RequestEntropy })
        assertTrue(ttlOne.effects.none { it is MeshEffect.RequestEntropy })
        assertEquals(1u.toUByte(), ttlTwo.entropyRequest().outgoingTtl)
        assertEquals(6u.toUByte(), ttlSeven.entropyRequest().outgoingTtl)
        assertEquals(6u.toUByte(), hostile.entropyRequest().outgoingTtl)
    }

    @Test
    fun authenticatedDuplicateDoesNotDispatchOrRequestRelayTwice() {
        val engine = MeshEngine()
        val first = admitUnsigned(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val duplicate = admitUnsigned(engine, first.state, MeshFixtures.broadcastPacket)

        assertEquals(1, duplicate.state.admittedPackets.size)
        assertTrue(duplicate.effects.none { it is MeshEffect.PublishPublicPayload })
        assertTrue(duplicate.effects.none { it is MeshEffect.RequestEntropy })
    }

    @Test
    fun oneEarliestDedupTimerExpiresOnlyAtItsExplicitDeadline() {
        val limits = MeshLimits()
        val engine = MeshEngine(limits)
        val admitted = admitUnsigned(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val timer = requireNotNull(admitted.state.dedupExpiryTimer)
        val early = engine.reduce(
            admitted.state,
            MeshEvent.TimerElapsed(
                correlationId = timer.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now.plus(1.minutes),
                timerId = timer.timerId,
            ),
        )
        val expired = engine.reduce(
            admitted.state,
            MeshEvent.TimerElapsed(
                correlationId = timer.correlationId,
                generation = MeshFixtures.generation,
                observedAt = timer.expiresAt,
                timerId = timer.timerId,
            ),
        )

        assertEquals(admitted.state, early.state)
        assertTrue(expired.state.admittedPackets.isEmpty())
        assertEquals(null, expired.state.dedupExpiryTimer)
    }

    @Test
    fun fullUnexpiredDedupCacheRejectsNewAdmissionWithoutWeakeningSentinel() {
        val limits = MeshLimits(maxAdmittedPacketIds = 1)
        val engine = MeshEngine(limits)
        val sentinel = PacketId.of(bytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        val initial = readyState(engine).copy(
            admittedPackets = SnapshotMap(
                mapOf(sentinel to AdmittedPacket(MeshFixtures.now.plus(5.minutes))),
            ),
        )
        val rejected = admitUnsigned(engine, initial, MeshFixtures.broadcastPacket)

        assertEquals(setOf(sentinel), rejected.state.admittedPackets.keys)
        assertTrue(rejected.effects.none { it is MeshEffect.PublishPublicPayload })
        assertTrue(rejected.effects.none { it is MeshEffect.RequestEntropy })
    }

    private fun readyState(engine: MeshEngine): MeshState =
        engine.reduce(
            MeshFixtures.state(),
            MeshEvent.LinkObserved(
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.Opened(
                    MeshFixtures.linkA,
                    LinkCapabilities(maxWriteBytes = 4096, writeReady = true),
                ),
            ),
        ).state

    private fun admitUnsigned(
        engine: MeshEngine,
        state: MeshState,
        packet: DecodedPacket,
        observedAt: MonotonicTime = MeshFixtures.now,
    ): Transition<MeshState, MeshEffect> {
        val received = engine.reduce(
            state,
            MeshEvent.LinkObserved(
                generation = MeshFixtures.generation,
                observedAt = observedAt,
                event = LinkEvent.PayloadReceived(MeshFixtures.linkA, packet.rawPacket.wireBytes),
            ),
        )
        val decode = assertIs<MeshEffect.DecodePacket>(received.effects.single())
        val decoded = engine.reduce(
            received.state,
            MeshEvent.PacketDecoded(
                correlationId = decode.correlationId,
                generation = MeshFixtures.generation,
                observedAt = observedAt,
                source = decode.source,
                result = DecodeResult.Success(packet),
            ),
        )
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        return engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = MeshFixtures.generation,
                observedAt = observedAt,
                result = MeshResult.Success(sha256Digest),
            ),
        )
    }

    private fun packetWithTtl(ttl: UByte): DecodedPacket {
        val bytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes.copyToByteArray()
        bytes[2] = ttl.toByte()
        return decode(Bytes.copyOf(bytes))
    }

    private fun Transition<MeshState, MeshEffect>.entropyRequest(): MeshEffect.RequestEntropy =
        effects.filterIsInstance<MeshEffect.RequestEntropy>().single()

    private fun decode(bytes: Bytes): DecodedPacket =
        assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(bytes)).value

    private companion object {
        val sha256Digest: Bytes =
            bytes("25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda")
        val localRecipientPacket: DecodedPacket = decode(
            bytes("02020301020304050607080100000002001122334455667700112233445566774142"),
        )
        val nonlocalRecipientPacket: DecodedPacket = decode(
            bytes("0202030102030405060708010000000200112233445566778899aabbccddeeff4142"),
        )
        val fragmentPacket: DecodedPacket = decode(
            bytes(
                "0220030102030405060708000000001a0011223344556677" +
                    "0001020304050607000000020202020301020304050607080000",
            ),
        )

        fun bytes(hex: String): Bytes = MeshFixtures.bytes(hex)

        fun decode(bytes: Bytes): DecodedPacket =
            assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(bytes)).value
    }
}
