package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.protocol.bitchat.SigningTranscript
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.testing.compatibility.CompatibilityFixture
import com.yet.bitmessage.testing.compatibility.FixtureManifestParser
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CanonicalMeshScenarioTest {
    private val manifest by lazy {
        FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json")).also {
            assertTrue(FixtureManifestParser.validate(it).isEmpty())
        }
    }

    @Test
    fun realSimulationShaMatchesCanonicalPacketIdentity() {
        val evidence = fixture("apple-phase4-packet-identity")
        val packet = decoded(bytes(evidence.wireBytesHex))
        val input = PacketIdentity.input(packet).canonicalBytes
        val digest = SimulationSha256.digest(input)

        assertEquals(evidence.semantic("packetIdentityInputHex"), input.hex())
        assertEquals(evidence.semantic("packetIdentitySha256"), digest.hex())
        assertEquals(evidence.semantic("packetIdHex"), PacketIdentity.fromSha256(digest).value.hex())
    }

    @Test
    fun canonicalSignedPacketRelaysAcrossThreeNodesWithoutTranscriptMutation() = runTest {
        val evidence = fixture("apple-phase4-signing-relay")
        val original = decoded(bytes(evidence.wireBytesHex))
        val transcript = assertIs<DecodeResult.Success<Bytes>>(SigningTranscript.build(original)).value
        assertEquals(256, transcript.size)
        assertEquals(evidence.signingTranscriptHex, transcript.hex())
        assertEquals(evidence.signingTranscriptSha256, SimulationSha256.digest(transcript).hex())

        val input = assertIs<EncodeResult.Success>(RelayEncoding.withTtl(original, 3u)).bytes
        val snapshot = runSignedLine(input)
        val relay = snapshot.deliveries.single { it.targetNode == nodeC }
        val expectedRelay = assertIs<EncodeResult.Success>(RelayEncoding.withTtl(original, 2u)).bytes
        val packetId = PacketIdentity.fromSha256(
            SimulationSha256.digest(PacketIdentity.input(original).canonicalBytes),
        )

        assertEquals(2u.toUByte(), relay.ttl)
        assertEquals(packetId, relay.packetId)
        assertEquals(snapshot.node(nodeB).state.admittedPackets.keys,
            snapshot.node(nodeC).state.admittedPackets.keys)
        assertEquals(SimulationSha256.digest(expectedRelay), relay.wireSha256)
        assertEquals(SimulationSha256.digest(bytes(evidence.semantic("signatureHex"))), relay.signatureSha256)
        assertEquals(SimulationSha256.digest(transcript), relay.signingTranscriptSha256)
    }

    @Test
    fun canonicalSevenRelaysAsSixButHostileClampRemainsLocalPolicy() = runTest {
        val evidence = fixture("apple-phase4-signing-relay")
        val original = decoded(bytes(evidence.wireBytesHex))
        val receivedTtl = evidence.semantic("receivedTtl").toUByte()
        val relayedTtl = evidence.semantic("relayedTtl").toUByte()
        assertEquals(receivedTtl, original.ttl)
        val expectedWire = bytes(evidence.semantic("relayedWireBytesHex"))

        val canonical = runSignedLine(original.rawPacket.wireBytes)
        val canonicalRelay = canonical.deliveries.single { it.targetNode == nodeC }
        assertEquals(relayedTtl, canonicalRelay.ttl)
        assertEquals(SimulationSha256.digest(expectedWire), canonicalRelay.wireSha256)

        // This mutation is a documented local hostile-input clamp, not a new upstream fixture.
        val hostileInput = assertIs<EncodeResult.Success>(RelayEncoding.withTtl(original, 255u)).bytes
        val hostile = runSignedLine(hostileInput)
        val hostileRelay = hostile.deliveries.single { it.targetNode == nodeC }
        assertEquals(relayedTtl, hostileRelay.ttl)
        assertEquals(SimulationSha256.digest(expectedWire), hostileRelay.wireSha256)
        assertEquals(canonicalRelay.signingTranscriptSha256, hostileRelay.signingTranscriptSha256)
    }

    private suspend fun TestScope.runSignedLine(input: Bytes): SimulationSnapshot {
        val network = SimulatedNetwork(backgroundScope)
        network.addNode(SimulatedNodeConfig(nodeA, peer(1), protocolSeed = 11))
        network.addNode(SimulatedNodeConfig(nodeB, peer(11), protocolSeed = 22,
            verificationPlan = VerificationPlan.valid(1)))
        network.addNode(SimulatedNodeConfig(nodeC, peer(21), protocolSeed = 33,
            verificationPlan = VerificationPlan.valid(1)))
        network.connect(SimulatedConnection.create("ab", nodeA, nodeB, 4_096, 100.milliseconds))
        network.connect(SimulatedConnection.create("bc", nodeB, nodeC, 4_096, 100.milliseconds))
        network.injectTransportWrite(SimulatedLinkId.of("ab:a-to-b"), input)
        val result = assertIs<RunUntilResult.Reached>(network.runUntil(
            predicate = { it.node(nodeC).publications.size == 1 },
            maxProcessedEvents = 5_000,
            maxVirtualDuration = 30.seconds,
        ))
        network.close()
        return result.snapshot
    }

    private fun fixture(id: String): CompatibilityFixture = manifest.fixtures.single { it.id == id }

    private fun CompatibilityFixture.semantic(id: String): String = semanticFields.single { it.id == id }.value

    private fun decoded(wire: Bytes): DecodedPacket =
        assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value

    private fun resource(path: String): String = checkNotNull(javaClass.classLoader?.getResource(path)) {
        "Missing compatibility resource $path"
    }.readText()

    private fun bytes(hex: String): Bytes = Bytes.copyOf(ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    })

    private fun Bytes.hex(): String = copyToByteArray().joinToString("") { "%02x".format(it) }

    private fun peer(firstByte: Int): WirePeerId = WirePeerId.of(
        Bytes.copyOf(ByteArray(8) { (firstByte + it).toByte() }),
    )

    private companion object {
        val nodeA = SimulatedNodeId.of("A")
        val nodeB = SimulatedNodeId.of("B")
        val nodeC = SimulatedNodeId.of("C")
    }
}
