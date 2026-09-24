package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayLoopScenarioTest {
    @Test
    fun triangleRelayLoopConvergesWhilePassiveExpiryTimersRemainQueued() = runTest {
        val a = SimulatedNodeId.of("A")
        val b = SimulatedNodeId.of("B")
        val c = SimulatedNodeId.of("C")
        val network = SimulatedNetwork(backgroundScope)
        network.addNode(SimulatedNodeConfig(a, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(b, peer(11), protocolSeed = 22))
        network.addNode(SimulatedNodeConfig(c, peer(21), protocolSeed = 33))
        network.connect(SimulatedConnection.create("ab", a, b, 4_096, 100.milliseconds))
        network.connect(SimulatedConnection.create("bc", b, c, 4_096, 100.milliseconds))
        network.connect(SimulatedConnection.create("ca", c, a, 4_096, 100.milliseconds))
        val packet = SimulationFixtures.messagePacket(ttl = 7u)
        val packetId = PacketIdentity.fromSha256(
            SimulationSha256.digest(PacketIdentity.input(packet).canonicalBytes),
        )
        network.injectTransportWrite(SimulatedLinkId.of("ab:a-to-b"), packet.rawPacket.wireBytes)

        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { snapshot ->
                snapshot.deliveries.any { it.packetId == packetId } &&
                    snapshot.nodes.all { it.state.scheduledRelays.isEmpty() } &&
                    snapshot.pendingEvents.none { it.category.isPacketNetworkWork }
            },
            maxProcessedEvents = 20_000,
            maxVirtualDuration = 30.seconds,
        ))
        val snapshot = reached.snapshot
        assertTrue(snapshot.nodes.all { node -> node.publications.count { it.packetId == packetId } <= 1 })
        assertTrue(snapshot.pendingEvents.any { it.category == ScheduledCategory.RUNTIME_TIMER })
        assertTrue(snapshot.pendingEvents.any { it.deadline > snapshot.now })
        assertTrue(snapshot.processedEvents < 20_000)
        network.close()
    }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )
}
