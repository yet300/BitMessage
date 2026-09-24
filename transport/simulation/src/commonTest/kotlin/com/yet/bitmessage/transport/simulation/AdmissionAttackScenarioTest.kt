package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdmissionAttackScenarioTest {
    private val a = SimulatedNodeId.of("A")
    private val b = SimulatedNodeId.of("B")
    private val ab = SimulatedLinkId.of("ab:a-to-b")

    @Test
    fun invalidAuthenticationFloodCannotPoisonAdmittedDedup() = runTest {
        val limits = MeshLimits(
            maxPendingAdmissions = 4,
            maxPendingAdmissionsPerLink = 2,
            maxAdmittedPacketIds = 2,
        )
        val network = SimulatedNetwork(backgroundScope)
        network.addNode(SimulatedNodeConfig(a, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(b, peer(11), protocolSeed = 22,
            meshLimits = limits, verificationPlan = VerificationPlan.valid(33)))
        network.connect(SimulatedConnection.create("ab", a, b, 4_096, 100.milliseconds))

        val invalidWires = (0 until 32).map { index ->
            SimulationFixtures.signedMessageWire(index.toByte(), timestamp = (index + 1).toULong())
        }
        val validWire = SimulationFixtures.signedMessageWire(0x7f, timestamp = 1_000u)
        invalidWires.forEach { network.injectTransportWrite(ab, it) }
        network.injectTransportWrite(ab, validWire)

        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(b).publications.size == 1 },
            maxProcessedEvents = 20_000,
            maxVirtualDuration = 30.seconds,
        ))
        val node = reached.snapshot.node(b)
        val validId = packetId(validWire)
        assertTrue(node.state.pendingAdmissions.size <= limits.maxPendingAdmissions)
        assertEquals(setOf(validId), node.state.admittedPackets.keys)
        assertEquals(listOf(validId), node.publications.map { it.packetId })
        assertTrue(invalidWires.map(::packetId).none { it in node.state.admittedPackets })
        assertEquals(33, reached.snapshot.deliveries.count { it.targetNode == b })
        network.close()
    }

    private fun packetId(wire: Bytes) = PacketIdentity.fromSha256(
        SimulationSha256.digest(PacketIdentity.input(
            assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value,
        ).canonicalBytes),
    )

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )
}
