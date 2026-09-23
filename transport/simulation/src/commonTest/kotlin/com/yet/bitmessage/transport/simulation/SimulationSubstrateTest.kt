package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.transport.api.LinkFailureCode
import com.yet.bitmessage.transport.api.LinkResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimulationSubstrateTest {
    private val nodeA = SimulatedNodeId.of("A")
    private val nodeB = SimulatedNodeId.of("B")
    private val nodeC = SimulatedNodeId.of("C")
    private val directionAB = SimulatedLinkId.of("ab:a-to-b")
    private val directionBA = SimulatedLinkId.of("ab:b-to-a")

    @Test
    fun payloadArrivesExactlyAtOneHundredMilliseconds() = runTest {
        val network = testNetwork()
        network.injectTransportWrite(directionAB, SimulationFixtures.messagePacket().rawPacket.wireBytes)

        network.advanceBy(99.milliseconds)
        assertTrue(network.snapshot().deliveries.isEmpty())
        network.advanceBy(1.milliseconds)
        assertEquals(MonotonicTime.ZERO.plus(100.milliseconds), network.snapshot().deliveries.single().deliveredAt)
        network.close()
    }

    @Test
    fun equalDeadlineDeliveriesUseInsertionSequence() = runTest {
        val network = testNetwork()
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        network.injectTransportWrite(directionAB, wire)
        network.injectTransportWrite(directionAB, wire)

        network.advanceBy(100.milliseconds)
        val deliveries = network.snapshot().deliveries
        assertEquals(2, deliveries.size)
        assertTrue(deliveries[0].sequence < deliveries[1].sequence)
        assertEquals(deliveries[0].deliveredAt, deliveries[1].deliveredAt)
        network.close()
    }

    @Test
    fun explicitDropProducesNoDelivery() = runTest {
        val network = testNetwork(FaultPlan(transmissionFaults = listOf(
            TransmissionFault.Drop(TransmissionSelector(directionAB, 1)),
        )))
        network.injectTransportWrite(directionAB, SimulationFixtures.messagePacket().rawPacket.wireBytes)

        network.advanceBy(100.milliseconds)
        assertTrue(network.snapshot().deliveries.isEmpty())
        assertEquals(listOf(TransmissionSelector(directionAB, 1)),
            network.snapshot().faultCursor.consumedTransmissionSelectors)
        network.close()
    }

    @Test
    fun explicitDuplicateProducesExactlyTwoDeliveries() = runTest {
        val network = testNetwork(FaultPlan(transmissionFaults = listOf(
            TransmissionFault.Duplicate(TransmissionSelector(directionAB, 1), copies = 2),
        )))
        network.injectTransportWrite(directionAB, SimulationFixtures.messagePacket().rawPacket.wireBytes)

        network.advanceBy(100.milliseconds)
        assertEquals(2, network.snapshot().deliveries.size)
        assertEquals(1, network.snapshot().node(nodeB).publications.size)
        network.close()
    }

    @Test
    fun explicitDelayLetsALaterWriteArriveFirst() = runTest {
        val network = testNetwork(FaultPlan(transmissionFaults = listOf(
            TransmissionFault.AddDelay(TransmissionSelector(directionAB, 1), 200.milliseconds),
        )))
        val first = SimulationFixtures.messagePacket()
        val second = SimulationFixtures.messagePacket(payload = Bytes.copyOf(byteArrayOf(0x55)))
        network.injectTransportWrite(directionAB, first.rawPacket.wireBytes)
        network.injectTransportWrite(directionAB, second.rawPacket.wireBytes)

        network.advanceBy(100.milliseconds)
        assertEquals(1, network.snapshot().deliveries.size)
        val firstArrival = network.snapshot().deliveries.single()
        assertEquals(packetId(second), firstArrival.packetId)
        assertEquals(MonotonicTime.ZERO.plus(100.milliseconds), firstArrival.deliveredAt)
        network.advanceBy(200.milliseconds)
        val deliveries = network.snapshot().deliveries
        assertEquals(2, deliveries.size)
        assertEquals(packetId(first), deliveries[1].packetId)
        assertEquals(MonotonicTime.ZERO.plus(300.milliseconds), deliveries[1].deliveredAt)
        network.close()
    }

    @Test
    fun partitionBlocksOnlySelectedDirectionsAndHealingRestoresFutureTraffic() = runTest {
        val plan = FaultPlan(timedLinkFaults = listOf(
            TimedLinkFault("partition", MonotonicTime.ZERO.plus(50.milliseconds),
                LinkFaultAction.Partition(listOf(directionAB), active = true)),
            TimedLinkFault("heal", MonotonicTime.ZERO.plus(150.milliseconds),
                LinkFaultAction.Partition(listOf(directionAB), active = false)),
        ))
        val network = testNetwork(plan)
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        network.injectTransportWrite(directionAB, wire)

        network.advanceBy(50.milliseconds)
        val aLink = network.snapshot().directions.single { it.id == directionAB }.source.linkId
        assertNull(network.snapshot().node(nodeA).state.links[aLink])
        assertIs<DirectedWriteDecision.Disconnected>(network.injectTransportWrite(directionAB, wire))
        assertIs<DirectedWriteDecision.Accepted>(network.injectTransportWrite(directionBA, wire))
        network.advanceBy(100.milliseconds)
        assertEquals(1, network.snapshot().deliveries.size)
        assertEquals(directionBA, network.snapshot().deliveries.single().directionId)
        assertEquals(MonotonicTime.ZERO.plus(150.milliseconds),
            network.snapshot().node(nodeA).state.links[aLink]?.observedAt)
        assertIs<DirectedWriteDecision.Accepted>(network.injectTransportWrite(directionAB, wire))
        network.advanceBy(100.milliseconds)
        assertEquals(listOf(directionBA, directionAB), network.snapshot().deliveries.map { it.directionId })
        assertEquals(listOf("heal", "partition"), network.snapshot().faultCursor.appliedTimedFaultIds)
        network.close()
    }

    @Test
    fun reconnectCreatesNewEndpointLifecycleWithoutReplayingLostTraffic() = runTest {
        val network = testNetwork(FaultPlan(timedLinkFaults = listOf(
            TimedLinkFault("reconnect", MonotonicTime.ZERO.plus(50.milliseconds),
                LinkFaultAction.Reconnect("ab")),
        )))
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        network.injectTransportWrite(directionAB, wire)

        network.advanceBy(100.milliseconds)
        assertTrue(network.snapshot().deliveries.isEmpty())
        val reconnected = network.snapshot().directions.single { it.id == directionAB }
        assertEquals(1L, reconnected.epoch)
        assertEquals(MonotonicTime.ZERO.plus(50.milliseconds),
            network.snapshot().node(nodeA).state.links[reconnected.source.linkId]?.observedAt)
        val reverse = network.snapshot().directions.single { it.id == directionBA }
        assertEquals(MonotonicTime.ZERO.plus(50.milliseconds),
            network.snapshot().node(nodeB).state.links[reverse.source.linkId]?.observedAt)
        network.injectTransportWrite(directionAB, wire)
        network.advanceBy(100.milliseconds)
        assertEquals(1, network.snapshot().deliveries.size)
        assertEquals(MonotonicTime.ZERO.plus(200.milliseconds), network.snapshot().deliveries.single().deliveredAt)
        network.close()
    }

    @Test
    fun readinessBackpressureAndExplicitFailureSuppressDelivery() = runTest {
        val plan = FaultPlan(
            transmissionFaults = listOf(
                TransmissionFault.CompleteWith(TransmissionSelector(directionAB, 1),
                    PlannedLinkResult.Failed(LinkFailureCode.TRANSIENT)),
            ),
            timedLinkFaults = listOf(
                TimedLinkFault("not-ready", MonotonicTime.ZERO.plus(50.milliseconds),
                    LinkFaultAction.SetReadiness(directionAB, ready = false)),
            ),
        )
        val network = testNetwork(plan)
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        assertEquals(DirectedWriteDecision.Failed(LinkFailureCode.TRANSIENT),
            network.injectTransportWrite(directionAB, wire))
        network.advanceBy(50.milliseconds)
        val aLink = network.snapshot().directions.single { it.id == directionAB }.source.linkId
        assertEquals(false, network.snapshot().node(nodeA).state.links[aLink]?.capabilities?.writeReady)
        assertIs<DirectedWriteDecision.Backpressured>(network.injectTransportWrite(directionAB, wire))
        network.advanceBy(100.milliseconds)
        assertTrue(network.snapshot().deliveries.isEmpty())
        assertEquals(listOf(TransmissionSelector(directionAB, 1)),
            network.snapshot().faultCursor.consumedTransmissionSelectors)
        network.close()
    }

    @Test
    fun runtimeOriginatedRelayWritesReceiveCorrelatedTypedFailures() = runTest {
        val outgoing = SimulatedLinkId.of("bc:a-to-b")
        val cases = listOf(
            PlannedLinkResult.Backpressured,
            PlannedLinkResult.Failed(LinkFailureCode.TRANSIENT),
        )
        for (planned in cases) {
            val plan = FaultPlan(transmissionFaults = listOf(
                TransmissionFault.CompleteWith(TransmissionSelector(outgoing, 1), planned),
            ))
            val network = testNetwork(plan)
            network.addNode(SimulatedNodeConfig(nodeC, peer(21), protocolSeed = 33))
            network.connect(SimulatedConnection.create("bc", nodeB, nodeC, 4_096, 100.milliseconds))
            network.injectTransportWrite(directionAB, SimulationFixtures.messagePacket().rawPacket.wireBytes)

            val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
                predicate = { snapshot -> snapshot.linkCompletions.any { it.nodeId == nodeB } },
                maxProcessedEvents = 10_000,
                maxVirtualDuration = 30.seconds,
            ))
            val completion = reached.snapshot.linkCompletions.single { it.nodeId == nodeB }
            when (planned) {
                PlannedLinkResult.Backpressured -> assertIs<LinkResult.Backpressured>(completion.result)
                is PlannedLinkResult.Failed -> assertEquals(planned.code,
                    assertIs<LinkResult.Failed>(completion.result).code)
                else -> error("Unexpected test result")
            }
            assertEquals(reached.snapshot.node(nodeB).state.generation, completion.result.generation)
            assertTrue(reached.snapshot.node(nodeB).state.pendingLinkWrites.isEmpty())
            assertTrue(reached.snapshot.node(nodeC).publications.isEmpty())
            network.close()
        }
    }

    @Test
    fun mtuOverflowReturnsPayloadTooLargeWithoutDelivery() = runTest {
        val network = testNetwork(mtu = 16)
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        assertEquals(16, assertIs<DirectedWriteDecision.PayloadTooLarge>(
            network.injectTransportWrite(directionAB, wire),
        ).maximumBytes)
        network.advanceBy(100.milliseconds)
        assertTrue(network.snapshot().deliveries.isEmpty())
        network.close()
    }

    @Test
    fun scheduledQueueOverflowFailsBeforeAnyDuplicateIsScheduled() = runTest {
        val network = testNetwork(
            faultPlan = FaultPlan(transmissionFaults = listOf(
                TransmissionFault.Duplicate(TransmissionSelector(directionAB, 3), copies = 3),
            )),
            limits = SimulationLimits(maxScheduledEvents = 4),
        )
        network.runCurrentUntilQuiescent()
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        network.injectTransportWrite(directionAB, wire)
        network.injectTransportWrite(directionAB, wire)
        val before = network.diagnostics()
        assertEquals(2, before.pendingEventCount)
        assertFailsWith<SimulationLimitExceededException> {
            network.injectTransportWrite(directionAB, wire)
        }
        val after = network.diagnostics()
        assertEquals(before.pendingEventCount, after.pendingEventCount)
        assertEquals(before.lastTrace, after.lastTrace)
        assertEquals(before.processedEvents, after.processedEvents)
        // A limit is sticky: a scenario cannot subsequently report a successful snapshot.
        assertFailsWith<SimulationLimitExceededException> { network.snapshot() }
        assertFailsWith<SimulationLimitExceededException> {
            network.injectTransportWrite(directionAB, wire)
        }
        assertFailsWith<SimulationLimitExceededException> { network.stopNode(nodeA) }
        assertFailsWith<SimulationLimitExceededException> { network.startNode(nodeA) }
        network.close()
    }

    @Test
    fun malformedPayloadStopsAtProtocolAdapterWithoutMutatingMeshState() = runTest {
        val network = testNetwork()
        network.runCurrentUntilQuiescent()
        val before = network.snapshot().node(nodeB)
        network.injectTransportWrite(directionAB, Bytes.copyOf(byteArrayOf()))

        network.advanceBy(100.milliseconds)
        val after = network.snapshot().node(nodeB)
        assertEquals(before.state, after.state)
        assertEquals(1L, after.structuralDecodeRejections - before.structuralDecodeRejections)
        assertTrue(after.publications.isEmpty())
        assertEquals(0, network.snapshot().pendingEvents.count { it.category == ScheduledCategory.PAYLOAD_DELIVERY })
        network.close()
    }

    @Test
    fun bidirectionalConnectionCreatesTwoIndependentDirections() {
        val connection = SimulatedConnection.create(
            id = "ab",
            first = nodeA,
            second = nodeB,
            mtu = 512,
            latency = 100.milliseconds,
        )

        assertEquals(nodeA, connection.aToB.source.nodeId)
        assertEquals(nodeB, connection.aToB.target.nodeId)
        assertEquals(nodeB, connection.bToA.source.nodeId)
        assertEquals(nodeA, connection.bToA.target.nodeId)
        assertEquals(directionAB, connection.aToB.id)
        assertEquals(SimulatedLinkId.of("ab:b-to-a"), connection.bToA.id)
        assertEquals(connection.aToB.source.linkId, connection.bToA.target.linkId)
        assertEquals(connection.aToB.target.linkId, connection.bToA.source.linkId)
        assertNotEquals(connection.aToB.source.linkId, connection.aToB.target.linkId)
        assertNotEquals(connection.aToB.id, connection.bToA.id)
        assertEquals(100.milliseconds, connection.aToB.latency)
        assertEquals(100.milliseconds, connection.bToA.latency)
    }

    @Test
    fun writeValidationReturnsTypedReadinessMtuAndDisconnectOutcomes() {
        val direction = readyDirection(mtu = 4)
        assertIs<DirectedWriteDecision.PayloadTooLarge>(direction.decide(5))
        assertEquals(4, (direction.decide(5) as DirectedWriteDecision.PayloadTooLarge).maximumBytes)
        assertIs<DirectedWriteDecision.Backpressured>(direction.copy(writeReady = false).decide(4))
        assertIs<DirectedWriteDecision.Disconnected>(direction.copy(open = false).decide(4))
        assertIs<DirectedWriteDecision.Accepted>(direction.decide(4))
        assertFailsWith<IllegalArgumentException> { direction.decide(-1) }
    }

    @Test
    fun directedLinkAndConnectionRejectInvalidGeometry() {
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create("ab", nodeA, nodeA, 4, Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create(" ", nodeA, nodeB, 4, Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create(" ab", nodeA, nodeB, 4, Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create("ab ", nodeA, nodeB, 4, Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create("ab", nodeA, nodeB, 0, Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create("ab", nodeA, nodeB, 4, (-1).milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulatedConnection.create("ab", nodeA, nodeB, 4, Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> { readyDirection().copy(epoch = -1) }
        assertFailsWith<IllegalArgumentException> {
            readyDirection().copy(target = SimulatedEndpoint(nodeA, LinkId.of("other")))
        }
    }

    @Test
    fun faultPlanMatchesOnlyExplicitDirectionAndOrdinalAndIsBounded() {
        val plan = FaultPlan(
            transmissionFaults = listOf(
                TransmissionFault.Drop(TransmissionSelector(directionAB, writeOrdinal = 2)),
                TransmissionFault.Duplicate(TransmissionSelector(directionAB, writeOrdinal = 3), copies = 2),
            ),
            timedLinkFaults = emptyList(),
            maximumActions = 2,
        )

        assertNull(plan.decision(directionAB, 1))
        assertIs<TransmissionFault.Drop>(plan.decision(directionAB, 2))
        assertIs<TransmissionFault.Duplicate>(plan.decision(directionAB, 3))
        assertNull(plan.decision(SimulatedLinkId.of("ab:b-to-a"), 2))
        assertFailsWith<IllegalArgumentException> { plan.decision(directionAB, 0) }
        assertFailsWith<IllegalArgumentException> {
            FaultPlan(plan.transmissionFaults, emptyList(), maximumActions = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            FaultPlan(listOf(TransmissionFault.Drop(TransmissionSelector(directionAB, 2))),
                listOf(TimedLinkFault("close", MonotonicTime.ZERO, LinkFaultAction.SetOpen(directionAB, false))),
                maximumActions = 1)
        }
    }

    @Test
    fun transmissionFaultVariantsValidateBoundsAndSelectors() {
        val selector = TransmissionSelector(directionAB, 1)
        assertFailsWith<IllegalArgumentException> { TransmissionSelector(directionAB, 0) }
        assertFailsWith<IllegalArgumentException> { TransmissionFault.Duplicate(selector, copies = 1) }
        assertFailsWith<IllegalArgumentException> { TransmissionFault.Duplicate(selector, copies = 9) }
        assertFailsWith<IllegalArgumentException> { TransmissionFault.AddDelay(selector, (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { TransmissionFault.AddDelay(selector, Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { TransmissionFault.CorruptByte(selector, -1, 1u) }
        assertFailsWith<IllegalArgumentException> { TransmissionFault.CorruptByte(selector, 0, 0u) }
        assertEquals(
            PlannedLinkResult.Failed(LinkFailureCode.TRANSIENT),
            TransmissionFault.CompleteWith(selector, PlannedLinkResult.Failed(LinkFailureCode.TRANSIENT)).result,
        )
        assertFailsWith<IllegalArgumentException> {
            FaultPlan(listOf(TransmissionFault.Drop(selector), TransmissionFault.Duplicate(selector, 2)))
        }
    }

    @Test
    fun timedFaultsRequireDistinctIdsAndDefensivePartitionCopies() {
        val mutableDirections = mutableListOf(directionAB)
        val action = LinkFaultAction.Partition(mutableDirections, active = true)
        val source = mutableListOf(
            TimedLinkFault("partition", MonotonicTime.ZERO, action),
        )
        val plan = FaultPlan(timedLinkFaults = source)
        mutableDirections += SimulatedLinkId.of("other")
        source.clear()

        assertEquals(1, plan.timedLinkFaults.size)
        assertEquals("partition", plan.timedFault("partition")?.id)
        assertNull(plan.timedFault("missing"))
        assertEquals(
            listOf(directionAB),
            assertIs<LinkFaultAction.Partition>(plan.timedFault("partition")?.action).directionIds,
        )
        assertFailsWith<IllegalArgumentException> {
            FaultPlan(timedLinkFaults = listOf(
                TimedLinkFault("same", MonotonicTime.ZERO, LinkFaultAction.SetOpen(directionAB, false)),
                TimedLinkFault("same", MonotonicTime.ZERO, LinkFaultAction.SetReadiness(directionAB, true)),
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            TimedLinkFault(" ", MonotonicTime.ZERO, LinkFaultAction.SetOpen(directionAB, false))
        }
        assertFailsWith<IllegalArgumentException> { LinkFaultAction.Partition(emptyList(), true) }
        assertFailsWith<IllegalArgumentException> { LinkFaultAction.Partition(listOf(directionAB, directionAB), true) }
        assertFailsWith<IllegalArgumentException> { LinkFaultAction.Reconnect(" ") }
    }

    private fun readyDirection(mtu: Int = 4) = DirectedSimulatedLink(
        id = directionAB,
        source = SimulatedEndpoint(nodeA, LinkId.of("ab:a")),
        target = SimulatedEndpoint(nodeB, LinkId.of("ab:b")),
        mtu = mtu,
        latency = Duration.ZERO,
    )

    private suspend fun TestScope.testNetwork(
        faultPlan: FaultPlan = FaultPlan(),
        limits: SimulationLimits = SimulationLimits(),
        mtu: Int = 4_096,
    ): SimulatedNetwork = SimulatedNetwork(backgroundScope, limits, faultPlan).also { network ->
        network.addNode(SimulatedNodeConfig(nodeA, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(nodeB, peer(11), protocolSeed = 22))
        network.connect(SimulatedConnection.create("ab", nodeA, nodeB, mtu, 100.milliseconds))
    }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )

    private fun packetId(packet: DecodedPacket) =
        PacketIdentity.fromSha256(SimulationSha256.digest(PacketIdentity.input(packet).canonicalBytes))
}
