package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.transport.api.LinkFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SimulationSubstrateTest {
    private val nodeA = SimulatedNodeId.of("A")
    private val nodeB = SimulatedNodeId.of("B")
    private val directionAB = SimulatedLinkId.of("ab:a-to-b")

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
}
