package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.FragmentPayload
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.KnownPacketType
import com.yet.bitmessage.protocol.bitchat.PacketType
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FragmentScenarioTest {
    private val a = SimulatedNodeId.of("A")
    private val b = SimulatedNodeId.of("B")
    private val ab = SimulatedLinkId.of("ab:a-to-b")

    @Test
    fun reorderedDuplicatedAndDelayedFragmentsReenterFullAdmissionOnce() = runTest {
        val plan = FaultPlan(transmissionFaults = listOf(
            TransmissionFault.AddDelay(TransmissionSelector(ab, 1), 150.milliseconds),
        ))
        val network = twoNodes(plan = plan)
        val inner = SimulationFixtures.messagePacket(ttl = 0u).rawPacket.wireBytes
        val fragments = SimulationFixtures.fragmentOuterWires(inner, fragmentBytes = 13)
        assertEquals(2, fragments.size)
        fragments.reversed().forEach { network.injectTransportWrite(ab, it) }
        network.injectTransportWrite(ab, fragments.first())

        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(b).publications.size == 1 },
            maxProcessedEvents = 10_000,
            maxVirtualDuration = 30.seconds,
        ))
        val node = reached.snapshot.node(b)
        assertEquals(1, node.publications.size)
        assertTrue(node.state.fragmentStreams.isEmpty())
        assertEquals(0, node.state.aggregateFragmentBytes)
        assertTrue(packetId(inner) in node.state.admittedPackets)
        assertTrue(node.state.pendingAdmissions.isEmpty())
        network.close()
    }

    @Test
    fun conflictingRepeatedIndexDestroysExistingStream() = runTest {
        val network = twoNodes()
        val fragments = SimulationFixtures.fragmentOuterWires(
            SimulationFixtures.messagePacket(ttl = 0u).rawPacket.wireBytes, fragmentBytes = 13,
        )
        val first = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(fragments.first())).value
        val payload = assertIs<DecodeResult.Success<FragmentPayload>>(
            FragmentPayloadCodec.decode(first.payload),
        ).value
        val altered = payload.copy(data = Bytes.copyOf(payload.data.copyToByteArray().also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }))
        val conflict = SimulationFixtures.decodeOnlyOuterPacket(
            PacketType.of(KnownPacketType.FRAGMENT.value),
            assertIs<com.yet.bitmessage.protocol.bitchat.EncodeResult.Success>(
                FragmentPayloadCodec.encode(altered),
            ).bytes,
            timestamp = 999u,
        )
        network.injectTransportWrite(ab, fragments.first())
        network.advanceBy(100.milliseconds)
        assertEquals(1, network.snapshot().node(b).state.fragmentStreams.size)

        network.injectTransportWrite(ab, conflict)
        network.advanceBy(100.milliseconds)
        assertTrue(network.snapshot().node(b).state.fragmentStreams.isEmpty())
        assertEquals(0, network.snapshot().node(b).state.aggregateFragmentBytes)
        network.injectTransportWrite(ab, fragments.last())
        network.advanceBy(100.milliseconds)
        assertTrue(network.snapshot().node(b).publications.isEmpty())
        network.close()
    }

    @Test
    fun twelveUniqueStreamsStayWithinQuotasAndExpiryRestoresUse() = runTest {
        val limits = MeshLimits(
            maxFragmentStreams = 3,
            maxFragmentStreamsPerSource = 2,
            maxFragmentStreamBytes = 64,
            maxAggregateFragmentBytes = 64,
            fragmentLifetime = 1.seconds,
        )
        val network = twoNodes(meshLimits = limits)
        val inner = SimulationFixtures.messagePacket(ttl = 0u).rawPacket.wireBytes
        repeat(12) { index ->
            val fragment = SimulationFixtures.fragmentOuterWires(inner, 13, streamOrdinal = index + 1).first()
            network.injectTransportWrite(ab, fragment)
            network.advanceBy(100.milliseconds)
            val state = network.snapshot().node(b).state
            assertTrue(state.fragmentStreams.size <= limits.maxFragmentStreams)
            assertTrue(state.aggregateFragmentBytes <= limits.maxAggregateFragmentBytes)
            assertTrue(state.fragmentStreams.keys.groupingBy { it.sourcePeer }.eachCount().values.all {
                it <= limits.maxFragmentStreamsPerSource
            })
        }
        network.advanceBy(2.seconds)
        assertTrue(network.snapshot().node(b).state.fragmentStreams.isEmpty())

        val legitimate = SimulationFixtures.fragmentOuterWires(inner, 13, streamOrdinal = 100)
        legitimate.forEach { network.injectTransportWrite(ab, it) }
        val reached = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(b).publications.size == 1 },
            maxProcessedEvents = 10_000,
            maxVirtualDuration = 30.seconds,
        ))
        assertTrue(packetId(inner) in reached.snapshot.node(b).state.admittedPackets)
        assertEquals(1, reached.snapshot.node(b).publications.size)
        network.close()
    }

    private suspend fun TestScope.twoNodes(
        plan: FaultPlan = FaultPlan(),
        meshLimits: MeshLimits = MeshLimits(),
    ): SimulatedNetwork = SimulatedNetwork(backgroundScope, faultPlan = plan).also { network ->
        network.addNode(SimulatedNodeConfig(a, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(b, peer(11), protocolSeed = 22, meshLimits = meshLimits))
        network.connect(SimulatedConnection.create("ab", a, b, 4_096, 100.milliseconds))
    }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )

    private fun packetId(wire: Bytes) = PacketIdentity.fromSha256(SimulationSha256.digest(
        PacketIdentity.input(assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value)
            .canonicalBytes,
    ))
}
