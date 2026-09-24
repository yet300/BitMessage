package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFailureCode
import com.yet.bitmessage.engine.mesh.MeshResult
import com.yet.bitmessage.engine.mesh.PacketSource
import com.yet.bitmessage.engine.mesh.SnapshotList
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerDriver
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerDriverFactory
import com.yet.bitmessage.engine.mesh.runtime.StartResult
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.EntropyRequest
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.transport.api.LinkCloseReason
import com.yet.bitmessage.transport.api.LinkCommand
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SimulationEntropyTest {
    private val nodeA = SimulatedNodeId.of("A")

    @Test
    fun protocolEntropyHistoryHasALifetimeBoundWithoutChangingGeneratedBytes() {
        val bounded = NodeProtocolEntropy(seed = 91)
        val replay = NodeProtocolEntropy(seed = 91)
        repeat(1_100) { index ->
            val request = EntropyRequest(CorrelationId.of("entropy:$index"), 2)
            assertEquals(replay.generate(request).bytes, bounded.generate(request).bytes)
        }
        assertTrue(bounded.transcript.size <= 1_024)
        assertEquals(76L, bounded.droppedCount)
        assertEquals(bounded.transcript, replay.transcript)
    }

    @Test
    fun tinyEntropyHistoryDropsDiagnosticsAcrossRepeatedCallsWithoutChangingRandomness() {
        val bounded = NodeProtocolEntropy(seed = 31, maximumRecords = 2, maximumBytes = 4)
        val replay = NodeProtocolEntropy(seed = 31)
        repeat(3) { call ->
            repeat(4) { offset ->
                val request = EntropyRequest(CorrelationId.of("entropy:${call * 4 + offset}"), 2)
                assertEquals(replay.generate(request).bytes, bounded.generate(request).bytes)
            }
            assertTrue(bounded.transcript.size <= 2)
        }
        assertEquals(10L, bounded.droppedCount)
        assertEquals(replay.transcript.take(2), bounded.transcript)
    }

    @Test
    fun simulationPlanRandomnessDoesNotChangeNodeProtocolEntropy() {
        val first = NodeProtocolEntropy(seed = 91)
        val second = NodeProtocolEntropy(seed = 91)
        localFaultPlan(seed = 1)
        val firstBytes = first.generate(EntropyRequest(CorrelationId.of("entropy:1"), 16)).bytes
        localFaultPlan(seed = 999)
        val secondBytes = second.generate(EntropyRequest(CorrelationId.of("entropy:1"), 16)).bytes

        assertEquals(firstBytes, secondBytes)
        assertEquals(first.transcript, second.transcript)
        assertEquals(16, first.transcript.single().byteCount)
    }

    @Test
    fun protocolSeedDoesNotChangeTheExplicitCompiledFaultPlan() {
        val first = NodeProtocolEntropy(seed = 1)
        val firstPlan = localFaultPlan(seed = 41)
        val second = NodeProtocolEntropy(seed = 2)
        val secondPlan = localFaultPlan(seed = 41)

        assertEquals(firstPlan, secondPlan)
        assertEquals(firstPlan.decision(SimulatedLinkId.of("ab:a-to-b"), 1), secondPlan.decision(SimulatedLinkId.of("ab:a-to-b"), 1))
        assertTrue(first.generate(EntropyRequest(CorrelationId.of("entropy:1"), 16)).bytes !=
            second.generate(EntropyRequest(CorrelationId.of("entropy:1"), 16)).bytes)
    }

    @Test
    fun digestUsesRealShaByDefaultAndOnlyExplicitOrdinalOverrides() = runTest {
        val host = RecordingHost()
        val executor = SimulationEffectExecutor(nodeA, host, NodeProtocolEntropy(7), VerificationPlan.EMPTY, DigestPlan.failure(2))
        val first = digestEffect(1)
        val result = assertIs<MeshEvent.PacketDigestComputed>(executor.execute(first))
        assertEquals(SimulationSha256.digest(first.input.canonicalBytes), assertIs<MeshResult.Success<Bytes>>(result.result).value)
        assertEquals(MeshResult.Failure(MeshFailureCode.DIGEST_FAILED),
            assertIs<MeshEvent.PacketDigestComputed>(executor.execute(digestEffect(2))).result)
        val third = digestEffect(3)
        assertEquals(SimulationSha256.digest(third.input.canonicalBytes),
            assertIs<MeshResult.Success<Bytes>>(assertIs<MeshEvent.PacketDigestComputed>(executor.execute(third)).result).value)
    }

    @Test
    fun verificationDefaultsToInvalidAndRequiresAnExplicitValidOrdinal() = runTest {
        val host = RecordingHost()
        val executor = SimulationEffectExecutor(nodeA, host, NodeProtocolEntropy(7), VerificationPlan.valid(2), DigestPlan.EMPTY)
        assertEquals(MeshResult.Success(false),
            assertIs<MeshEvent.SignatureVerified>(executor.execute(verificationEffect(1))).result)
        assertEquals(MeshResult.Success(true),
            assertIs<MeshEvent.SignatureVerified>(executor.execute(verificationEffect(2))).result)
        assertEquals(MeshResult.Success(false),
            assertIs<MeshEvent.SignatureVerified>(executor.execute(verificationEffect(3))).result)
    }

    @Test
    fun delayedOutcomesAreScheduledWithOriginalCorrelationAndGeneration() = runTest {
        val host = RecordingHost()
        val digest = SimulationSha256.digest(SimulationFixtures.broadcastIdentityInput.canonicalBytes)
        val plan = DigestPlan(mapOf(1L to PlannedOutcome.Delayed(5.milliseconds, MeshResult.Success(digest))))
        val executor = SimulationEffectExecutor(nodeA, host, NodeProtocolEntropy(7), VerificationPlan.EMPTY, plan)
        val effect = digestEffect(1)

        assertNull(executor.execute(effect))
        val scheduled = host.scheduled.single()
        assertEquals(MonotonicTime.ZERO.plus(5.milliseconds), scheduled.deadline)
        val event = assertIs<MeshEvent.PacketDigestComputed>(scheduled.event)
        assertEquals(effect.correlationId, event.correlationId)
        assertEquals(effect.generation, event.generation)
        assertEquals(scheduled.deadline, event.observedAt)
        assertEquals(MeshResult.Success(digest), event.result)
    }

    @Test
    fun plansRejectInvalidOrdinalsDuplicateRulesAndInvalidDelay() {
        assertFailsWith<IllegalArgumentException> { VerificationPlan(mapOf(0L to PlannedOutcome.Immediate(MeshResult.Success(true)))) }
        assertFailsWith<IllegalArgumentException> { DigestPlan.failure(0) }
        assertFailsWith<IllegalArgumentException> { DigestPlan.staleCorrelation(0, CorrelationId.of("stale")) }
        assertFailsWith<IllegalArgumentException> { DigestPlan.staleGeneration(0, Generation(0)) }
        assertFailsWith<IllegalArgumentException> {
            VerificationPlan(listOf(
                1L to PlannedOutcome.Immediate(MeshResult.Success(true)),
                1L to PlannedOutcome.Immediate(MeshResult.Success(false)),
            ))
        }
        assertFailsWith<IllegalArgumentException> { PlannedOutcome.Delayed(Duration.INFINITE, MeshResult.Success(true)) }
        assertFailsWith<IllegalArgumentException> { PlannedOutcome.Delayed((-1).milliseconds, MeshResult.Success(true)) }
    }

    @Test
    fun fragmentRelayEntropyAndNetworkEffectsUseProductionBoundaries() = runTest {
        val host = RecordingHost()
        val entropy = NodeProtocolEntropy(19)
        val executor = SimulationEffectExecutor(nodeA, host, entropy, VerificationPlan.EMPTY, DigestPlan.EMPTY)
        val packet = SimulationFixtures.messagePacket()
        val packetId = PacketIdentity.fromSha256(SimulationSha256.digest(SimulationFixtures.broadcastIdentityInput.canonicalBytes))
        val source = PacketSource.Link(LinkId.of("incoming"))
        val fragment = MeshEffect.DecodeFragmentPayload(
            CorrelationId.of("fragment:1"), Generation(3), packetId, source, packet.sender, packet.payload,
        )
        assertEquals(FragmentPayloadCodec.decode(fragment.payload),
            assertIs<MeshEvent.FragmentPayloadDecoded>(executor.execute(fragment)).result)
        val relay = MeshEffect.EncodeRelay(
            CorrelationId.of("relay:1"), Generation(3), packetId, packet, 2u, SnapshotList(listOf(LinkId.of("outgoing"))),
        )
        assertEquals(RelayEncoding.withTtl(packet, 2u), assertIs<MeshEvent.RelayEncoded>(executor.execute(relay)).result)
        val entropyRequest = MeshEffect.RequestEntropy(
            CorrelationId.of("entropy:1"), Generation(3), packetId, source, 2u,
        )
        val entropyEvent = assertIs<MeshEvent.EntropyProvided>(executor.execute(entropyRequest))
        assertEquals(entropy.transcript.single().bytes, assertIs<MeshResult.Success<Bytes>>(entropyEvent.result).value)
        assertEquals(entropyRequest.correlationId, entropy.transcript.single().correlationId)

        val write = LinkCommand.Write(LinkId.of("outgoing"), CorrelationId.of("write:1"), Generation(3), packet.rawPacket.wireBytes)
        val close = LinkCommand.Close(LinkId.of("outgoing"), CorrelationId.of("close:1"), Generation(3), LinkCloseReason.LOCAL_REQUEST)
        assertNull(executor.execute(MeshEffect.WriteLink(write)))
        assertNull(executor.execute(MeshEffect.CloseLink(close)))
        assertEquals(listOf(write), host.writes)
        assertEquals(listOf(close), host.closes)
    }

    @Test
    fun delayedVerificationFailureAndExplicitDigestCollisionRemainCorrelated() = runTest {
        val host = RecordingHost()
        val forcedDigest = Bytes.copyOf(ByteArray(32) { 0x2a })
        val verification = VerificationPlan(mapOf(
            1L to PlannedOutcome.Delayed(2.milliseconds, MeshResult.Failure(MeshFailureCode.VERIFICATION_FAILED)),
        ))
        val executor = SimulationEffectExecutor(nodeA, host, NodeProtocolEntropy(7), verification, DigestPlan.collision(1, forcedDigest))

        assertEquals(forcedDigest,
            assertIs<MeshResult.Success<Bytes>>(assertIs<MeshEvent.PacketDigestComputed>(executor.execute(digestEffect(1))).result).value)
        assertNull(executor.execute(verificationEffect(1)))
        val scheduled = host.scheduled.single()
        assertEquals(MonotonicTime.ZERO.plus(2.milliseconds), scheduled.deadline)
        val result = assertIs<MeshEvent.SignatureVerified>(scheduled.event)
        assertEquals(CorrelationId.of("verify:1"), result.correlationId)
        assertEquals(Generation(3), result.generation)
        assertEquals(MeshResult.Failure(MeshFailureCode.VERIFICATION_FAILED), result.result)
    }

    @Test
    fun explicitDigestPlanCanReturnAStaleCorrelationWithoutChangingTheRealDigest() = runTest {
        val host = RecordingHost()
        val staleCorrelation = CorrelationId.of("digest:old-request")
        val executor = SimulationEffectExecutor(
            nodeA,
            host,
            NodeProtocolEntropy(7),
            VerificationPlan.EMPTY,
            DigestPlan.staleCorrelation(requestOrdinal = 1, correlationId = staleCorrelation),
        )
        val effect = digestEffect(1)

        val result = assertIs<MeshEvent.PacketDigestComputed>(executor.execute(effect))
        assertEquals(staleCorrelation, result.correlationId)
        assertEquals(effect.generation, result.generation)
        assertEquals(SimulationSha256.digest(effect.input.canonicalBytes), assertIs<MeshResult.Success<Bytes>>(result.result).value)

        val staleGenerationExecutor = SimulationEffectExecutor(
            nodeA,
            host,
            NodeProtocolEntropy(7),
            VerificationPlan.EMPTY,
            DigestPlan.staleGeneration(requestOrdinal = 1, generation = Generation(2)),
        )
        val staleGeneration = assertIs<MeshEvent.PacketDigestComputed>(staleGenerationExecutor.execute(effect))
        assertEquals(effect.correlationId, staleGeneration.correlationId)
        assertEquals(Generation(2), staleGeneration.generation)
    }

    @Test
    fun realNodeStartsAndPublishesOnlyBoundedRedactedProjections() = runTest {
        lateinit var node: SimulatedNode
        val host = RecordingHost(onPublication = { node.record(it) })
        val peer = SimulationFixtures.messagePacket().sender
        node = SimulatedNode(
            config = SimulatedNodeConfig(nodeA, peer, protocolSeed = 17),
            parentScope = backgroundScope,
            host = host,
            timerDriverFactory = MeshTimerDriverFactory { NoopTimerDriver },
            maximumPublications = 1,
            maximumEntropyRecords = 2,
            maximumEntropyBytes = 4,
        )

        assertEquals(StartResult.Started, node.start(MonotonicTime.ZERO))
        assertEquals(Generation(0), node.snapshot().state.generation)
        val publication = MeshEffect.PublishPublicPayload(
            correlationId = CorrelationId.of("publication:1"),
            generation = Generation(0),
            packetId = PacketIdentity.fromSha256(SimulationSha256.digest(SimulationFixtures.broadcastIdentityInput.canonicalBytes)),
            sender = peer,
            ingressLink = LinkId.of("inbound"),
            timestamp = 42u,
            payload = Bytes.copyOf(byteArrayOf(1, 2, 3)),
        )
        val executor = SimulationEffectExecutor(nodeA, host, NodeProtocolEntropy(17), VerificationPlan.EMPTY, DigestPlan.EMPTY)
        assertNull(executor.execute(publication))
        assertEquals(3, node.snapshot().publications.single().payloadSize)
        assertEquals(nodeA, node.snapshot().publications.single().nodeId)
        assertFailsWith<SimulationLimitExceededException> { executor.execute(publication) }
        assertFailsWith<SimulationLimitExceededException> { node.snapshot() }
        node.stop(MonotonicTime.ZERO)
        node.close(MonotonicTime.ZERO)
    }

    private fun localFaultPlan(seed: Int): FaultPlan {
        val random = Random(seed)
        val ordinal = random.nextInt(1, 10).toLong()
        return FaultPlan(
            transmissionFaults = listOf(
                TransmissionFault.Drop(TransmissionSelector(SimulatedLinkId.of("ab:a-to-b"), ordinal)),
            ),
        )
    }

    private fun digestEffect(ordinal: Int) = MeshEffect.ComputePacketDigest(
        correlationId = CorrelationId.of("digest:$ordinal"),
        generation = Generation(3),
        input = SimulationFixtures.broadcastIdentityInput,
    )

    private fun verificationEffect(ordinal: Int) = MeshEffect.VerifySignature(
        correlationId = CorrelationId.of("verify:$ordinal"),
        generation = Generation(3),
        sender = WirePeerId.of(Bytes.copyOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))),
        transcript = Bytes.copyOf(byteArrayOf(1, 2, 3)),
        signature = Bytes.copyOf(byteArrayOf(4, 5, 6)),
    )

    private data class Scheduled(val deadline: MonotonicTime, val event: MeshEvent)

    private class RecordingHost(
        private val onPublication: (MeshEffect.PublishPublicPayload) -> Unit = {},
    ) : SimulationEffectHost {
        override val now: MonotonicTime = MonotonicTime.ZERO
        val scheduled = mutableListOf<Scheduled>()
        val writes = mutableListOf<LinkCommand.Write>()
        val closes = mutableListOf<LinkCommand.Close>()
        override suspend fun scheduleMeshEvent(nodeId: SimulatedNodeId, deadline: MonotonicTime, event: MeshEvent) {
            scheduled += Scheduled(deadline, event)
        }
        override suspend fun submitWrite(nodeId: SimulatedNodeId, command: LinkCommand.Write) {
            writes += command
        }
        override suspend fun submitClose(nodeId: SimulatedNodeId, command: LinkCommand.Close) {
            closes += command
        }
        override suspend fun recordPublication(nodeId: SimulatedNodeId, effect: MeshEffect.PublishPublicPayload) =
            onPublication(effect)
    }

    private object NoopTimerDriver : MeshTimerDriver {
        override suspend fun schedule(request: com.yet.bitmessage.engine.mesh.runtime.MeshTimerRequest) = Unit
        override suspend fun cancel(key: com.yet.bitmessage.engine.mesh.runtime.MeshTimerKey) = Unit
        override suspend fun cancelAll() = Unit
    }
}
