package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DirectAndRelayScenarioTest {
    private val a = SimulatedNodeId.of("A")
    private val b = SimulatedNodeId.of("B")
    private val c = SimulatedNodeId.of("C")

    @Test
    fun directTransportDeliveryTraversesAdapterRuntimeAdmissionAndPublication() = runTest {
        val network = lineNetwork(includeC = false)
        network.injectTransportWrite(ab, SimulationFixtures.messagePacket().rawPacket.wireBytes)

        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(b).publications.size == 1 },
            maxProcessedEvents = 1_000,
            maxVirtualDuration = 5.seconds,
        ))
        val node = reached.snapshot.node(b)
        assertEquals(1, node.publications.size)
        assertEquals(1, node.state.admittedPackets.size)
        assertTrue(node.state.pendingAdmissions.isEmpty())
        assertEquals(1, reached.snapshot.deliveries.size)
        network.close()
    }

    @Test
    fun threeNodeRelayUsesRealMeshTtlIdentityAndWrites() = runTest {
        val network = lineNetwork(includeC = true)
        network.injectTransportWrite(ab, SimulationFixtures.messagePacket(ttl = 3u).rawPacket.wireBytes)

        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(c).publications.size == 1 },
            maxProcessedEvents = 5_000,
            maxVirtualDuration = 30.seconds,
        ))
        val snapshot = reached.snapshot
        assertEquals(1, snapshot.node(b).publications.size)
        assertEquals(1, snapshot.node(c).publications.size)
        assertEquals(snapshot.node(b).state.admittedPackets.keys, snapshot.node(c).state.admittedPackets.keys)
        assertEquals(2u.toUByte(), snapshot.deliveries.single { it.targetNode == c }.ttl)
        assertEquals(1, snapshot.linkCompletions.count { it.nodeId == b })
        network.close()
    }

    private suspend fun TestScope.lineNetwork(includeC: Boolean): SimulatedNetwork =
        SimulatedNetwork(backgroundScope).also { network ->
            network.addNode(SimulatedNodeConfig(a, peer(1), protocolSeed = 11))
            network.addNode(SimulatedNodeConfig(b, peer(11), protocolSeed = 22))
            if (includeC) network.addNode(SimulatedNodeConfig(c, peer(21), protocolSeed = 33))
            network.connect(SimulatedConnection.create("ab", a, b, 4_096, 100.milliseconds))
            if (includeC) network.connect(SimulatedConnection.create("bc", b, c, 4_096, 100.milliseconds))
        }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )

    private companion object {
        val ab = SimulatedLinkId.of("ab:a-to-b")
    }
}
