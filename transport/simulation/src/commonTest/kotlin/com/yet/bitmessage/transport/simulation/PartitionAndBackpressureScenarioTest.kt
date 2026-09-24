package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.transport.api.LinkResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PartitionAndBackpressureScenarioTest {
    private val a = SimulatedNodeId.of("A")
    private val b = SimulatedNodeId.of("B")
    private val c = SimulatedNodeId.of("C")
    private val ab = SimulatedLinkId.of("ab:a-to-b")
    private val bc = SimulatedLinkId.of("bc:a-to-b")

    @Test
    fun partitionPreventsImpossibleDeliveryAndHealingDoesNotReplayLostTraffic() = runTest {
        val plan = FaultPlan(timedLinkFaults = listOf(
            TimedLinkFault("partition", at(100.milliseconds), LinkFaultAction.Partition(listOf(bc), true)),
            TimedLinkFault("heal", at(500.milliseconds), LinkFaultAction.Partition(listOf(bc), false)),
        ))
        val network = lineNetwork(plan)
        network.advanceTo(at(100.milliseconds))
        network.injectTransportWrite(ab, SimulationFixtures.messagePacket(
            payload = Bytes.copyOf(byteArrayOf(1)), timestamp = 1u,
        ).rawPacket.wireBytes)

        network.advanceTo(at(500.milliseconds))
        assertTrue(network.snapshot().node(c).publications.isEmpty())
        assertTrue(network.snapshot().deliveries.none { it.targetNode == c })

        val second = SimulationFixtures.messagePacket(
            payload = Bytes.copyOf(byteArrayOf(2)), timestamp = 2u,
        )
        network.injectTransportWrite(ab, second.rawPacket.wireBytes)
        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(c).publications.size == 1 },
            maxProcessedEvents = 5_000,
            maxVirtualDuration = 30.seconds,
        ))
        assertEquals(1, reached.snapshot.node(c).publications.size)
        assertEquals(reached.snapshot.deliveries.last { it.targetNode == b }.packetId,
            reached.snapshot.node(c).publications.single().packetId)
        network.close()
    }

    @Test
    fun oldGenerationCompletionCannotMutateRestartedRuntime() = runTest {
        val network = lineNetwork(bcLatency = 3.seconds)
        network.injectTransportWrite(ab, SimulationFixtures.messagePacket().rawPacket.wireBytes)
        val pending = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(b).state.pendingLinkWrites.isNotEmpty() },
            maxProcessedEvents = 5_000,
            maxVirtualDuration = 5.seconds,
        )).snapshot
        val oldGeneration = pending.node(b).state.generation
        val oldCompletion = pending.pendingEvents.single {
            it.category == ScheduledCategory.MESH_EVENT && it.nodeId == b && it.deadline > pending.now
        }.deadline

        network.stopNode(b)
        network.startNode(b)
        network.runCurrentUntilQuiescent()
        val restarted = network.snapshot().node(b).state
        assertNotEquals(oldGeneration, restarted.generation)
        val bLinks = network.snapshot().directions.filter { it.source.nodeId == b && it.open }
        assertTrue(bLinks.all { it.source.linkId in restarted.links })

        network.advanceTo(oldCompletion)
        assertEquals(restarted, network.snapshot().node(b).state)
        assertTrue(network.snapshot().linkCompletions.any {
            it.nodeId == b && it.result.generation == oldGeneration
        })
        network.close()
    }

    @Test
    fun forcedBackpressureAndFailureCompleteOnceWithoutDeliveryOrRetry() = runTest {
        for (result in listOf(PlannedLinkResult.Backpressured,
            PlannedLinkResult.Failed(com.yet.bitmessage.transport.api.LinkFailureCode.TRANSIENT))) {
            val plan = FaultPlan(transmissionFaults = listOf(
                TransmissionFault.CompleteWith(TransmissionSelector(bc, 1), result),
            ))
            val network = lineNetwork(plan)
            network.injectTransportWrite(ab, SimulationFixtures.messagePacket().rawPacket.wireBytes)
            val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
                predicate = { it.linkCompletions.any { completion -> completion.nodeId == b } },
                maxProcessedEvents = 5_000,
                maxVirtualDuration = 30.seconds,
            ))
            val completion = reached.snapshot.linkCompletions.single { it.nodeId == b }
            when (result) {
                PlannedLinkResult.Backpressured -> assertIs<LinkResult.Backpressured>(completion.result)
                is PlannedLinkResult.Failed -> assertEquals(result.code,
                    assertIs<LinkResult.Failed>(completion.result).code)
                else -> error("Unexpected test result")
            }
            assertTrue(reached.snapshot.node(b).state.pendingLinkWrites.isEmpty())
            network.advanceBy(1.seconds)
            assertTrue(network.snapshot().deliveries.none { it.targetNode == c })
            assertEquals(1, network.snapshot().linkCompletions.count { it.nodeId == b })
            network.close()
        }
    }

    @Test
    fun readinessChangeBlocksRelayWithoutInventingARetry() = runTest {
        val plan = FaultPlan(timedLinkFaults = listOf(
            TimedLinkFault("not-ready", MonotonicTime.ZERO,
                LinkFaultAction.SetReadiness(bc, ready = false)),
            TimedLinkFault("ready", at(500.milliseconds),
                LinkFaultAction.SetReadiness(bc, ready = true)),
        ))
        val network = lineNetwork(plan)
        network.injectTransportWrite(ab, SimulationFixtures.messagePacket(
            payload = Bytes.copyOf(byteArrayOf(1)), timestamp = 1u,
        ).rawPacket.wireBytes)
        network.advanceTo(at(500.milliseconds))
        assertEquals(1, network.snapshot().node(b).publications.size)
        assertTrue(network.snapshot().node(c).publications.isEmpty())
        assertTrue(network.snapshot().node(b).state.pendingLinkWrites.isEmpty())
        assertTrue(network.snapshot().linkCompletions.isEmpty())

        network.injectTransportWrite(ab, SimulationFixtures.messagePacket(
            payload = Bytes.copyOf(byteArrayOf(2)), timestamp = 2u,
        ).rawPacket.wireBytes)
        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(c).publications.size == 1 },
            maxProcessedEvents = 5_000,
            maxVirtualDuration = 30.seconds,
        ))
        assertEquals(1, reached.snapshot.linkCompletions.count { it.nodeId == b })
        network.close()
    }

    private suspend fun TestScope.lineNetwork(
        plan: FaultPlan = FaultPlan(),
        bcLatency: Duration = 100.milliseconds,
    ): SimulatedNetwork = SimulatedNetwork(backgroundScope, faultPlan = plan).also { network ->
        network.addNode(SimulatedNodeConfig(a, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(b, peer(11), protocolSeed = 22))
        network.addNode(SimulatedNodeConfig(c, peer(21), protocolSeed = 33))
        network.connect(SimulatedConnection.create("ab", a, b, 4_096, 100.milliseconds))
        network.connect(SimulatedConnection.create("bc", b, c, 4_096, bcLatency))
    }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )

    private fun at(duration: Duration) = MonotonicTime.ZERO.plus(duration)
}
