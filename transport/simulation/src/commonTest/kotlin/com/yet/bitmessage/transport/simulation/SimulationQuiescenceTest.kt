package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimulationQuiescenceTest {
    @Test
    fun currentQuiescenceDrainsDeliveryButLeavesMaintenanceTimersQueued() = runTest {
        val network = twoNodeNetwork()
        val wire = SimulationFixtures.messagePacket().rawPacket.wireBytes
        network.injectTransportWrite(SimulatedLinkId.of("ab:a-to-b"), wire)

        assertIs<QuiescenceResult.Quiescent>(network.runCurrentUntilQuiescent())
        assertEquals(MonotonicTime.ZERO, network.now)
        assertTrue(network.snapshot().node(nodeB).publications.isEmpty())

        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(100.milliseconds))
        val snapshot = network.snapshot()
        assertEquals(1, snapshot.node(nodeB).publications.size)
        assertEquals(MonotonicTime.ZERO.plus(100.milliseconds), snapshot.now)
        assertTrue(snapshot.pendingEvents.any {
            it.category == ScheduledCategory.RUNTIME_TIMER && it.deadline > snapshot.now
        })
        network.close()
    }

    @Test
    fun runUntilStopsAtPredicateWithoutExpiringFutureDedup() = runTest {
        val network = twoNodeNetwork()
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )

        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(nodeB).publications.size == 1 },
            maxProcessedEvents = 1_000,
            maxVirtualDuration = 30.seconds,
        ))
        assertEquals(MonotonicTime.ZERO.plus(100.milliseconds), reached.snapshot.now)
        assertTrue(reached.snapshot.pendingEvents.any { it.deadline >= reached.snapshot.now.plus(4.minutes) })
        network.close()
    }

    @Test
    fun explicitAdvanceFiresNetworkOwnedRuntimeTimer() = runTest {
        val network = twoNodeNetwork()
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )
        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(100.milliseconds))
        assertEquals(1, network.snapshot().node(nodeB).state.admittedPackets.size)

        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(5.minutes))
        assertTrue(network.snapshot().node(nodeB).state.admittedPackets.isEmpty())
        network.close()
    }

    @Test
    fun directAdvanceSettlesRestartEffectsBeforeMovingVirtualTime() = runTest {
        val network = twoNodeNetwork()
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )
        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(100.milliseconds))
        assertEquals(1, network.snapshot().node(nodeB).state.admittedPackets.size)
        network.stopNode(nodeB)
        network.startNode(nodeB)

        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(6.minutes))
        assertTrue(network.snapshot().node(nodeB).state.admittedPackets.isEmpty())
        network.close()
    }

    @Test
    fun impossiblePredicateIsUnreachableWithoutScheduledWork() = runTest {
        val network = SimulatedNetwork(backgroundScope)
        assertIs<RunUntilResult.Unreachable>(network.runUntil(
            predicate = { false },
            maxProcessedEvents = 8,
            maxVirtualDuration = 1.seconds,
        ))
        network.close()
    }

    @Test
    fun eventLimitDoesNotConsumeAnUndispatchedEvent() = runTest {
        val network = twoNodeNetwork()
        assertIs<QuiescenceResult.EventLimitExceeded>(network.runCurrentUntilQuiescent(1))
        assertEquals(1, network.snapshot().pendingEvents.count { it.deadline == network.now })
        assertIs<QuiescenceResult.Quiescent>(network.runCurrentUntilQuiescent(8))
        network.close()
    }

    @Test
    fun effectLimitReportsWorkAlreadyProcessedByRuntimeFence() = runTest {
        val network = twoNodeNetwork()
        assertIs<QuiescenceResult.Quiescent>(network.runCurrentUntilQuiescent())
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )

        val exceeded = assertIs<QuiescenceResult.EventLimitExceeded>(
            network.advanceBy(100.milliseconds, maxProcessedEvents = 1),
        )
        assertTrue(exceeded.diagnostics.processedEvents > 1)
        assertEquals(exceeded.diagnostics.processedEvents, network.snapshot().processedEvents)
        network.close()
    }

    @Test
    fun predicateExecutionRespectsVirtualDurationLimit() = runTest {
        val network = twoNodeNetwork(latency = 2.seconds)
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )
        assertIs<RunUntilResult.VirtualDurationExceeded>(network.runUntil(
            predicate = { false },
            maxProcessedEvents = 100,
            maxVirtualDuration = 1.seconds,
        ))
        assertEquals(MonotonicTime.ZERO, network.now)
        network.close()
    }

    @Test
    fun structuralDecodeFailureNeverAdmitsAPacket() = runTest {
        val network = twoNodeNetwork()
        assertIs<QuiescenceResult.Quiescent>(network.runCurrentUntilQuiescent())
        val before = network.snapshot().node(nodeB).state
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            Bytes.copyOf(byteArrayOf(0x01, 0x02, 0x03)),
        )
        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(100.milliseconds))
        val after = network.snapshot().node(nodeB)
        assertEquals(before.pendingAdmissions, after.state.pendingAdmissions)
        assertEquals(before.admittedPackets, after.state.admittedPackets)
        assertEquals(1L, after.structuralDecodeRejections)
        network.close()
    }

    @Test
    fun deliveryDuringRuntimeSuspensionIsDroppedWithoutBecomingALimitFailure() = runTest {
        val network = twoNodeNetwork()
        assertIs<QuiescenceResult.Quiescent>(network.runCurrentUntilQuiescent())
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )
        network.stopNode(nodeB)

        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(100.milliseconds))
        assertEquals(1, network.snapshot().deliveries.size)
        assertTrue(network.snapshot().node(nodeB).publications.isEmpty())
        assertTrue(network.snapshot().trace.any {
            it.category == TraceCategory.PAYLOAD_DELIVERED && it.outcome == TraceOutcome.REJECTED
        })
        network.startNode(nodeB)
        network.close()
    }

    @Test
    fun realRuntimePublicationOverflowCannotBeSwallowedAsEffectFailed() = runTest {
        val network = twoNodeNetwork(limits = SimulationLimits(maxPublicationRecords = 1))
        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket().rawPacket.wireBytes,
        )
        assertIs<QuiescenceResult.Quiescent>(network.advanceBy(100.milliseconds))
        assertEquals(1, network.snapshot().node(nodeB).publications.size)

        network.injectTransportWrite(
            SimulatedLinkId.of("ab:a-to-b"),
            SimulationFixtures.messagePacket(payload = Bytes.copyOf(byteArrayOf(0x55)))
                .rawPacket.wireBytes,
        )
        assertFailsWith<SimulationLimitExceededException> {
            network.advanceBy(100.milliseconds)
        }
        assertFailsWith<SimulationLimitExceededException> { network.snapshot() }
        network.close()
    }

    @Test
    fun networkLimitsCannotBeBypassedByALargerFaultPlanLimit() = runTest {
        val direction = SimulatedLinkId.of("ab:a-to-b")
        val plan = FaultPlan(
            transmissionFaults = listOf(
                TransmissionFault.Drop(TransmissionSelector(direction, 1)),
                TransmissionFault.Drop(TransmissionSelector(direction, 2)),
            ),
            maximumActions = 2,
        )
        assertFailsWith<SimulationLimitExceededException> {
            SimulatedNetwork(backgroundScope, SimulationLimits(maxFaultActions = 1), plan)
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.twoNodeNetwork(
        latency: kotlin.time.Duration = 100.milliseconds,
        limits: SimulationLimits = SimulationLimits(),
    ): SimulatedNetwork {
        val network = SimulatedNetwork(backgroundScope, limits)
        network.addNode(SimulatedNodeConfig(nodeA, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(nodeB, peer(11), protocolSeed = 22))
        network.connect(SimulatedConnection.create(
            id = "ab",
            first = nodeA,
            second = nodeB,
            mtu = 4_096,
            latency = latency,
        ))
        return network
    }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )

    private companion object {
        val nodeA = SimulatedNodeId.of("A")
        val nodeB = SimulatedNodeId.of("B")
    }
}
