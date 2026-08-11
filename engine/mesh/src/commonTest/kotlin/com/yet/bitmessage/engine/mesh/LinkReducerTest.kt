package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TimerId
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
    fun decodedPacketStartsAdmissionWithoutACodecEffect() {
        val engine = MeshEngine()
        val state = stateWithReadyLink(engine)
        val transition = engine.reduce(state, MeshFixtures.packetDecoded())

        val digest = transition.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        assertEquals("mesh:3:digest:0", digest.correlationId.value)
        assertEquals(PacketSource.Link(MeshFixtures.linkA), transition.state.pendingAdmissions.getValue(digest.correlationId).source)
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
        val rejected = engine.reduce(state, MeshFixtures.packetDecoded())

        assertEquals(state, rejected.state)
        assertTrue(rejected.effects.isEmpty())
    }

    @Test
    fun decodedPacketReservesBoundedPendingState() {
        val engine = MeshEngine()
        val success = engine.reduce(stateWithReadyLink(engine), MeshFixtures.packetDecoded())
        val digest = success.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()

        val pending = assertIs<PendingAdmission>(
            success.state.pendingAdmissions[digest.correlationId],
        )
        assertEquals(AdmissionStage.AwaitingDigest, pending.stage)
        assertEquals(MeshFixtures.broadcastPacket.rawPacket.wireBytes.size, success.state.aggregatePendingBytes)
        assertEquals(1, success.effects.count { it is MeshEffect.ComputePacketDigest })
        assertEquals(1, success.effects.count { it is MeshEffect.Schedule })

    }

    @Test
    fun staleGenerationAndUnknownIngressLinkCannotMutateState() {
        val engine = MeshEngine()
        val state = stateWithReadyLink(engine)
        val stale = engine.reduce(
            state,
            MeshFixtures.packetDecoded(eventGeneration = MeshFixtures.generation.next()),
        )
        val unknown = engine.reduce(
            state,
            MeshFixtures.packetDecoded(source = PacketSource.Link(MeshFixtures.linkB)),
        )

        assertEquals(state, stale.state)
        assertEquals(state, unknown.state)
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
            signingTranscript = null,
            stage = AdmissionStage.AwaitingDigest,
            retainedBytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes.size,
            expiresAt = MeshFixtures.now.plus(15.seconds),
            timeoutTimerId = TimerId.of(timer),
        )
}
