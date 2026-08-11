package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkCloseReason
import com.yet.bitmessage.transport.api.LinkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LinkReducerTest {
    @Test
    fun linkOpenReadinessAndCloseAreDeterministic() {
        val engine = MeshEngine()
        val opened = engine.reduce(
            MeshFixtures.state(),
            observed(LinkEvent.Opened(MeshFixtures.linkA, LinkCapabilities(128, writeReady = false))),
        )
        val active = assertIs<ActiveLink>(opened.state.links[MeshFixtures.linkA])
        assertEquals(false, active.capabilities.writeReady)

        val ready = engine.reduce(
            opened.state,
            observed(LinkEvent.ReadinessChanged(MeshFixtures.linkA, LinkCapabilities(256, writeReady = true))),
        )
        assertEquals(true, ready.state.links[MeshFixtures.linkA]?.capabilities?.writeReady)

        val closed = engine.reduce(
            ready.state,
            observed(LinkEvent.Closed(MeshFixtures.linkA, LinkCloseReason.REMOTE_CLOSED)),
        )
        assertTrue(closed.state.links.isEmpty())
    }

    @Test
    fun activeLinkCapacityRejectsGrowthWithoutEvictingLiveLinks() {
        val limits = MeshLimits(maxActiveLinks = 1, maxRelayFanout = 1)
        val engine = MeshEngine(limits)
        val first = engine.reduce(
            MeshFixtures.state(),
            observed(LinkEvent.Opened(MeshFixtures.linkA, LinkCapabilities(128, true))),
        )
        val rejected = engine.reduce(
            first.state,
            observed(LinkEvent.Opened(MeshFixtures.linkB, LinkCapabilities(128, true))),
        )

        assertEquals(setOf(MeshFixtures.linkA), rejected.state.links.keys)
        assertTrue(rejected.effects.isEmpty())
    }

    @Test
    fun bytesReceivedEmitsDecodeWithoutParsingOrPendingAllocationInsideTheEngine() {
        val engine = MeshEngine()
        val state = stateWithReadyLink(engine)
        val wire = MeshFixtures.broadcastPacket.rawPacket.wireBytes
        val transition = engine.reduce(
            state,
            observed(LinkEvent.PayloadReceived(MeshFixtures.linkA, wire)),
        )

        val decode = assertIs<MeshEffect.DecodePacket>(transition.effects.single())
        assertEquals(wire, decode.bytes)
        assertEquals(PacketSource.Link(MeshFixtures.linkA), decode.source)
        assertEquals("mesh:3:decode:0", decode.correlationId.value)
        assertTrue(transition.state.pendingAdmissions.isEmpty())
        assertEquals(1, transition.state.nextCorrelationSequence)
    }

    @Test
    fun oversizedPayloadIsRejectedBeforeCorrelationOrPendingGrowth() {
        val limits = MeshLimits(
            maxPendingPacketBytes = 25,
            maxAggregatePendingBytes = 25,
        )
        val engine = MeshEngine(limits)
        val state = stateWithReadyLink(engine)
        val rejected = engine.reduce(
            state,
            observed(
                LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                ),
            ),
        )

        assertEquals(state, rejected.state)
        assertTrue(rejected.effects.isEmpty())
    }

    @Test
    fun decodeSuccessReservesBoundedPendingStateAndDecodeFailureDoesNot() {
        val engine = MeshEngine()
        val decodeRequest = requestDecode(engine)
        val success = engine.reduce(
            decodeRequest.state,
            MeshEvent.PacketDecoded(
                correlationId = decodeRequest.effect.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                source = decodeRequest.effect.source,
                result = DecodeResult.Success(MeshFixtures.broadcastPacket),
            ),
        )

        val pending = assertIs<PendingAdmission>(
            success.state.pendingAdmissions[decodeRequest.effect.correlationId],
        )
        assertEquals(AdmissionStage.AwaitingDigest, pending.stage)
        assertEquals(MeshFixtures.broadcastPacket.rawPacket.wireBytes.size, success.state.aggregatePendingBytes)
        assertEquals(1, success.effects.count { it is MeshEffect.ComputePacketDigest })
        assertEquals(1, success.effects.count { it is MeshEffect.Schedule })

        val failedRequest = requestDecode(engine)
        val failed = engine.reduce(
            failedRequest.state,
            MeshEvent.PacketDecoded(
                correlationId = failedRequest.effect.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                source = failedRequest.effect.source,
                result = DecodeResult.Failure(DecodeError.INVALID_LENGTH),
            ),
        )
        assertTrue(failed.state.pendingAdmissions.isEmpty())
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun staleGenerationAndUnknownDecodeCorrelationCannotMutateState() {
        val engine = MeshEngine()
        val request = requestDecode(engine)
        val stale = engine.reduce(
            request.state,
            MeshEvent.PacketDecoded(
                correlationId = request.effect.correlationId,
                generation = MeshFixtures.generation.next(),
                observedAt = MeshFixtures.now,
                source = request.effect.source,
                result = DecodeResult.Success(MeshFixtures.broadcastPacket),
            ),
        )
        val unknown = engine.reduce(
            request.state,
            MeshEvent.PacketDecoded(
                correlationId = CorrelationId.of("foreign-decode"),
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                source = request.effect.source,
                result = DecodeResult.Success(MeshFixtures.broadcastPacket),
            ),
        )

        assertEquals(request.state, stale.state)
        assertEquals(request.state, unknown.state)
        assertTrue(stale.effects.isEmpty())
        assertTrue(unknown.effects.isEmpty())
    }

    @Test
    fun linkCloseRemovesLinkScopedPendingAndBindingsInStableCorrelationOrder() {
        val engine = MeshEngine()
        val base = stateWithReadyLink(engine)
        val retainedBytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes.size
        val pendingA = pending("timer-a")
        val pendingB = pending("timer-b")
        val state = base.copy(
            provisionalBindings = SnapshotMap(
                mapOf(
                    PeerLinkKey(MeshFixtures.localPeer, MeshFixtures.linkA) to
                        PeerBinding(
                            peer = MeshFixtures.localPeer,
                            linkId = MeshFixtures.linkA,
                            expiresAt = MeshFixtures.now.plus(30.seconds),
                        ),
                ),
            ),
            pendingAdmissions = SnapshotMap(
                mapOf(
                    CorrelationId.of("pending-b") to pendingB,
                    CorrelationId.of("pending-a") to pendingA,
                ),
            ),
            aggregatePendingBytes = retainedBytes * 2,
        )

        val closed = engine.reduce(
            state,
            observed(LinkEvent.Closed(MeshFixtures.linkA, LinkCloseReason.REMOTE_CLOSED)),
        )

        assertTrue(closed.state.links.isEmpty())
        assertTrue(closed.state.provisionalBindings.isEmpty())
        assertTrue(closed.state.pendingAdmissions.isEmpty())
        assertEquals(0, closed.state.aggregatePendingBytes)
        assertEquals(
            listOf("pending-a", "pending-b"),
            closed.effects.map { assertIs<MeshEffect.Cancel>(it).correlationId.value },
        )
    }

    private data class DecodeRequest(
        val state: MeshState,
        val effect: MeshEffect.DecodePacket,
    )

    private fun requestDecode(engine: MeshEngine): DecodeRequest {
        val state = stateWithReadyLink(engine)
        val transition = engine.reduce(
            state,
            observed(
                LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                ),
            ),
        )
        return DecodeRequest(transition.state, assertIs(transition.effects.single()))
    }

    private fun stateWithReadyLink(engine: MeshEngine): MeshState =
        engine.reduce(
            MeshFixtures.state(),
            observed(LinkEvent.Opened(MeshFixtures.linkA, LinkCapabilities(1024, true))),
        ).state

    private fun observed(event: LinkEvent): MeshEvent.LinkObserved =
        MeshEvent.LinkObserved(
            generation = MeshFixtures.generation,
            observedAt = MeshFixtures.now,
            event = event,
        )

    private fun pending(timer: String): PendingAdmission =
        PendingAdmission(
            source = PacketSource.Link(MeshFixtures.linkA),
            packet = MeshFixtures.broadcastPacket,
            stage = AdmissionStage.AwaitingDigest,
            retainedBytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes.size,
            expiresAt = MeshFixtures.now.plus(15.seconds),
            timeoutTimerId = TimerId.of(timer),
        )
}
