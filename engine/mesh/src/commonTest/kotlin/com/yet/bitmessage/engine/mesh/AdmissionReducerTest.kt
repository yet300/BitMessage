package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AdmissionReducerTest {
    @Test
    fun invalidSignatureNeverPoisonsAdmittedDedupOrCreatesTrustedSideEffects() {
        val engine = MeshEngine()
        val sentinel = PacketId.of(bytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        val initial = readyState(engine).copy(
            admittedPackets = SnapshotMap(
                mapOf(sentinel to AdmittedPacket(MeshFixtures.now.plus(5.minutes))),
            ),
        )
        val decoded = driveDecoded(engine, initial, signedPacket)
        val digest = assertIs<MeshEffect.ComputePacketDigest>(
            decoded.effects.single { it is MeshEffect.ComputePacketDigest },
        )
        val awaitingSignature = engine.reduce(
            decoded.state,
            digestSuccess(digest.correlationId),
        )
        val verify = assertIs<MeshEffect.VerifySignature>(
            awaitingSignature.effects.single { it is MeshEffect.VerifySignature },
        )
        val rejected = engine.reduce(
            awaitingSignature.state,
            signatureResult(verify.correlationId, authentic = false),
        )

        assertEquals(setOf(sentinel), rejected.state.admittedPackets.keys)
        assertTrue(rejected.state.pendingAdmissions.isEmpty())
        assertTrue(rejected.state.provisionalBindings.isEmpty())
        assertTrue(rejected.effects.none {
            it is MeshEffect.PublishPublicPayload || it is MeshEffect.RequestEntropy
        })
        assertTrue(rejected.trace.any {
            it.transitionName.value == "mesh.admission.signature_rejected"
        })
    }

    @Test
    fun validSignatureCrossesAdmissionBoundaryAndUnsignedMessageUsesNamedPolicy() {
        val engine = MeshEngine()
        val signedDecoded = driveDecoded(engine, readyState(engine), signedPacket)
        val signedDigest = signedDecoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val awaitingSignature = engine.reduce(
            signedDecoded.state,
            digestSuccess(signedDigest.correlationId),
        )
        val verify = awaitingSignature.effects.filterIsInstance<MeshEffect.VerifySignature>().single()
        val signedAdmitted = engine.reduce(
            awaitingSignature.state,
            signatureResult(verify.correlationId, authentic = true),
        )

        assertEquals(setOf(expectedPacketId), signedAdmitted.state.admittedPackets.keys)
        assertTrue(signedAdmitted.state.pendingAdmissions.isEmpty())
        assertEquals(1, signedAdmitted.state.provisionalBindings.size)
        assertTrue(signedAdmitted.state.topologyExpiryTimer != null)

        val unsignedDecoded = driveDecoded(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val unsignedDigest = unsignedDecoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val unsignedAdmitted = engine.reduce(
            unsignedDecoded.state,
            digestSuccess(unsignedDigest.correlationId),
        )

        assertEquals(setOf(expectedPacketId), unsignedAdmitted.state.admittedPackets.keys)
        assertTrue(unsignedAdmitted.effects.none { it is MeshEffect.VerifySignature })
    }

    @Test
    fun admissionTimeoutRemovesOnlyItsPendingCandidate() {
        val limits = MeshLimits()
        val engine = MeshEngine(limits)
        val decoded = driveDecoded(engine, readyState(engine), signedPacket)
        val schedule = decoded.effects.filterIsInstance<MeshEffect.Schedule>().single()
        val expiredAt = MeshFixtures.now.plus(limits.pendingAdmissionLifetime)

        val expired = engine.reduce(
            decoded.state,
            MeshEvent.TimerElapsed(
                correlationId = schedule.correlationId,
                generation = MeshFixtures.generation,
                observedAt = expiredAt,
                timerId = schedule.timerId,
            ),
        )

        assertTrue(expired.state.pendingAdmissions.isEmpty())
        assertEquals(0, expired.state.aggregatePendingBytes)
        assertTrue(expired.state.admittedPackets.isEmpty())
    }

    @Test
    fun oneTopologyTimerExpiresProvisionalBindings() {
        val limits = MeshLimits()
        val engine = MeshEngine(limits)
        val decoded = driveDecoded(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val admitted = engine.reduce(decoded.state, digestSuccess(digest.correlationId))
        val timer = requireNotNull(admitted.state.topologyExpiryTimer)

        val expired = engine.reduce(
            admitted.state,
            MeshEvent.TimerElapsed(
                correlationId = timer.correlationId,
                generation = MeshFixtures.generation,
                observedAt = timer.expiresAt,
                timerId = timer.timerId,
            ),
        )

        assertTrue(expired.state.provisionalBindings.isEmpty())
        assertTrue(expired.state.routeObservations.isEmpty())
        assertEquals(null, expired.state.topologyExpiryTimer)
    }

    @Test
    fun staleMismatchedAndMalformedDigestResultsCannotAdmit() {
        val engine = MeshEngine()
        val decoded = driveDecoded(engine, readyState(engine), signedPacket)
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val stale = engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = MeshFixtures.generation.next(),
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(sha256Digest),
            ),
        )
        val mismatched = engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = CorrelationId.of("foreign-digest"),
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(sha256Digest),
            ),
        )
        val malformed = engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(bytes("0011")),
            ),
        )

        assertEquals(decoded.state, stale.state)
        assertEquals(decoded.state, mismatched.state)
        assertTrue(stale.state.admittedPackets.isEmpty())
        assertTrue(mismatched.state.admittedPackets.isEmpty())
        assertTrue(malformed.state.pendingAdmissions.isEmpty())
        assertTrue(malformed.state.admittedPackets.isEmpty())
    }

    @Test
    fun staleOrMismatchedSignatureResultCannotAdmit() {
        val engine = MeshEngine()
        val decoded = driveDecoded(engine, readyState(engine), signedPacket)
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val awaiting = engine.reduce(decoded.state, digestSuccess(digest.correlationId))
        val verify = awaiting.effects.filterIsInstance<MeshEffect.VerifySignature>().single()
        val stale = engine.reduce(
            awaiting.state,
            MeshEvent.SignatureVerified(
                correlationId = verify.correlationId,
                generation = MeshFixtures.generation.next(),
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(true),
            ),
        )
        val mismatched = engine.reduce(
            awaiting.state,
            MeshEvent.SignatureVerified(
                correlationId = CorrelationId.of("foreign-verify"),
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(true),
            ),
        )

        assertEquals(awaiting.state, stale.state)
        assertEquals(awaiting.state, mismatched.state)
        assertTrue(stale.state.admittedPackets.isEmpty())
        assertTrue(mismatched.state.admittedPackets.isEmpty())
    }

    @Test
    fun pendingGlobalPerLinkAndBytePressureNeverEvictsALiveCandidate() {
        val limits = MeshLimits(
            maxPendingAdmissions = 1,
            maxPendingAdmissionsPerLink = 1,
            maxPendingPacketBytes = signedPacket.rawPacket.wireBytes.size,
            maxAggregatePendingBytes = signedPacket.rawPacket.wireBytes.size,
        )
        val engine = MeshEngine(limits)
        val first = driveDecoded(engine, readyState(engine), signedPacket)
        val rejected = engine.reduce(first.state, MeshFixtures.packetDecoded(signedPacket))

        assertEquals(setOf(first.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single().correlationId), rejected.state.pendingAdmissions.keys)
        assertEquals(signedPacket.rawPacket.wireBytes.size, rejected.state.aggregatePendingBytes)
        assertTrue(rejected.effects.isEmpty())
    }

    @Test
    fun repeatedUniqueInvalidCandidatesStayBoundedAndNeverDisplaceAdmittedSentinel() {
        val limits = MeshLimits(
            maxPendingAdmissions = 4,
            maxPendingAdmissionsPerLink = 4,
        )
        val engine = MeshEngine(limits)
        val sentinel = PacketId.of(bytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        var state = readyState(engine).copy(
            admittedPackets = SnapshotMap(
                mapOf(sentinel to AdmittedPacket(MeshFixtures.now.plus(5.minutes))),
            ),
        )

        repeat(limits.maxPendingAdmissions * 3) { index ->
            val packet = signedPacketWithPayloadByte(index)
            val decoded = driveDecoded(engine, state, packet)
            val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
            val awaiting = engine.reduce(decoded.state, digestSuccess(digest.correlationId))
            val verify = awaiting.effects.filterIsInstance<MeshEffect.VerifySignature>().single()
            val rejected = engine.reduce(
                awaiting.state,
                signatureResult(verify.correlationId, authentic = false),
            )
            state = rejected.state

            assertTrue(state.pendingAdmissions.size <= limits.maxPendingAdmissions)
            assertTrue(state.aggregatePendingBytes <= limits.maxAggregatePendingBytes)
            assertEquals(setOf(sentinel), state.admittedPackets.keys)
        }
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

    private fun driveDecoded(
        engine: MeshEngine,
        state: MeshState,
        packet: DecodedPacket,
    ): Transition<MeshState, MeshEffect> {
        return engine.reduce(state, MeshFixtures.packetDecoded(packet))
    }

    private fun digestSuccess(correlationId: CorrelationId): MeshEvent.PacketDigestComputed =
        MeshEvent.PacketDigestComputed(
            correlationId = correlationId,
            generation = MeshFixtures.generation,
            observedAt = MeshFixtures.now,
            result = MeshResult.Success(sha256Digest),
        )

    private fun signatureResult(
        correlationId: CorrelationId,
        authentic: Boolean,
    ): MeshEvent.SignatureVerified =
        MeshEvent.SignatureVerified(
            correlationId = correlationId,
            generation = MeshFixtures.generation,
            observedAt = MeshFixtures.now,
            result = MeshResult.Success(authentic),
        )

    private fun signedPacketWithPayloadByte(value: Int): DecodedPacket {
        return MeshFixtures.signedPacketWithPayload(Bytes.copyOf(byteArrayOf(value.toByte())))
    }

    private companion object {
        val signedPacket: DecodedPacket = MeshFixtures.signedPacket
        val sha256Digest: Bytes = MeshFixtures.fakeSha256Digest
        val expectedPacketId: PacketId = PacketId.of(
            Bytes.copyOf(MeshFixtures.fakeSha256Digest.copyToByteArray().copyOf(PacketId.BYTE_SIZE)),
        )

        fun bytes(hex: String): Bytes = MeshFixtures.bytes(hex)
    }
}
