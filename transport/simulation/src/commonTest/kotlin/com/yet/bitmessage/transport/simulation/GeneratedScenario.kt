package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class GeneratedTopology(val nodes: List<SimulatedNodeId>, val connections: List<Pair<SimulatedNodeId, SimulatedNodeId>>)

internal data class GeneratedScenario(
    val name: String,
    val seed: Int,
    val topology: GeneratedTopology,
    val actions: List<ScenarioAction>,
    val faultPlan: FaultPlan,
    val protocolSeeds: Map<SimulatedNodeId, Int>,
    val meshLimits: MeshLimits,
    val simulationLimits: SimulationLimits,
    val invalidAuthenticationPacketIds: List<PacketId>,
)

/** Randomness is consumed only here. The returned plan is an exact replay input. */
internal object GeneratedScenarioCompiler {
    fun compile(seed: Int, maximumActions: Int = 128): GeneratedScenario {
        require(maximumActions in 1..128)
        val random = Random(seed)
        val count = 2 + random.nextInt(3)
        val nodes = (0 until count).map { SimulatedNodeId.of("N$it") }
        val connections = (0 until count - 1).map { nodes[it] to nodes[it + 1] }
        val topology = GeneratedTopology(nodes, connections)
        val directions = connections.flatMapIndexed { index, _ ->
            listOf(SimulatedLinkId.of("e$index:a-to-b"), SimulatedLinkId.of("e$index:b-to-a"))
        }
        val countActions = minOf(maximumActions, 8 + random.nextInt(9))
        val invalidAuthenticationPacketIds = mutableListOf<PacketId>()
        val actions = (0 until countActions).map { ordinal ->
            val at = MonotonicTime.fromElapsed((ordinal * 20L + random.nextLong(10)).milliseconds)
            val direction = directions[random.nextInt(directions.size)]
            val wire = when (ordinal % 5) {
                0 -> Bytes.copyOf(byteArrayOf(0x7f, ordinal.toByte()))
                1 -> SimulationFixtures.signedMessageWire(ordinal.toByte(),
                    timestamp = (seed.toLong().and(0xffff) * 1_000 + ordinal).toULong()).also {
                    val decoded = (BitchatCodec.decode(it) as DecodeResult.Success<DecodedPacket>).value
                    invalidAuthenticationPacketIds += PacketIdentity.fromSha256(
                        SimulationSha256.digest(PacketIdentity.input(decoded).canonicalBytes))
                }
                else -> SimulationFixtures.messagePacket(
                    ttl = random.nextInt(0, 8).toUByte(),
                    payload = Bytes.copyOf(byteArrayOf(seed.toByte(), ordinal.toByte())),
                    timestamp = (seed.toLong().and(0xffff) * 1_000 + ordinal).toULong(),
                ).rawPacket.wireBytes
            }
            ScenarioAction.Inject(at, direction, wire)
        }
        val timedFaults = if (seed % 3 == 0) listOf(
            TimedLinkFault("partition", MonotonicTime.fromElapsed(75.milliseconds),
                LinkFaultAction.Partition(listOf(directions.first()), active = true)),
            TimedLinkFault("reconnect", MonotonicTime.fromElapsed(175.milliseconds),
                LinkFaultAction.Partition(listOf(directions.first()), active = false)),
        ) else emptyList()
        val transmissionFaults = if (seed % 4 == 0) listOf(
            TransmissionFault.Duplicate(TransmissionSelector(directions.first(), 1), copies = 2),
        ) else emptyList()
        return GeneratedScenario(
            name = "generated-mesh-$seed", seed = seed, topology = topology,
            actions = actions, faultPlan = FaultPlan(transmissionFaults, timedFaults),
            protocolSeeds = nodes.associateWith { random.nextInt() },
            meshLimits = MeshLimits(), simulationLimits = SimulationLimits(),
            invalidAuthenticationPacketIds = invalidAuthenticationPacketIds.toList(),
        )
    }
}

internal data class ScenarioExecution(val finalSnapshot: SimulationSnapshot)

internal class SimulationFailure(
    val scenarioName: String,
    val seed: Int,
    val topology: GeneratedTopology,
    val actions: List<ScenarioAction>,
    val faultPlan: FaultPlan,
    val protocolSeeds: Map<SimulatedNodeId, Int>,
    val virtualTime: MonotonicTime,
    val eventCount: Long,
    val stateSizes: Map<SimulatedNodeId, Int>,
    val lastTrace: List<SimulationTraceRecord>,
    private val reason: String,
) : AssertionError(reason) {
    fun render(): String = buildString {
        append("scenario=").append(scenarioName).append(" seed=").append(seed)
        append(" topology=").append(topology).append(" actions=").append(actions)
        append(" faults=").append(faultPlan).append(" protocolSeeds=").append(protocolSeeds)
        append(" virtualTime=").append(virtualTime).append(" eventCount=").append(eventCount)
        append(" stateSizes=").append(stateSizes).append(" lastTrace=").append(lastTrace.takeLast(64))
        append(" reason=").append(reason)
    }

    override fun toString(): String = render()
}

internal suspend fun executeScenario(
    scenario: GeneratedScenario,
    scope: CoroutineScope,
    eventLimit: Int = scenario.simulationLimits.maxProcessedEvents,
    onSnapshot: (SimulationSnapshot) -> Unit = {},
): ScenarioExecution {
    val network = SimulatedNetwork(scope, scenario.simulationLimits, scenario.faultPlan)
    try {
        scenario.topology.nodes.forEachIndexed { index, id ->
            val peer = WirePeerId.of(Bytes.copyOf(ByteArray(8) { (index * 16 + it).toByte() }))
            network.addNode(SimulatedNodeConfig(id, peer, scenario.protocolSeeds.getValue(id), scenario.meshLimits))
        }
        scenario.topology.connections.forEachIndexed { index, pair ->
            network.connect(SimulatedConnection.create("e$index", pair.first, pair.second, 4_096, 5.milliseconds))
        }
        network.scheduleScenarioActions(scenario.actions)
        val finalDeadline = (scenario.actions.maxOfOrNull { it.at } ?: MonotonicTime.ZERO).plus(1.seconds)
        val result = network.advanceTo(finalDeadline, eventLimit)
        val snapshot = when (result) {
            is QuiescenceResult.Quiescent -> result.snapshot
            is QuiescenceResult.EventLimitExceeded -> throw failure(scenario, network,
                result.diagnostics, "event limit")
        }
        try {
            onSnapshot(snapshot)
        } catch (violation: AssertionError) {
            throw SimulationFailure(scenario.name, scenario.seed, scenario.topology, scenario.actions,
                scenario.faultPlan, scenario.protocolSeeds, snapshot.now, snapshot.processedEvents,
                snapshot.nodes.associate { it.id to
                    (it.state.pendingAdmissions.size + it.state.admittedPackets.size) },
                snapshot.trace.takeLast(64), violation.message ?: "scenario invariant failed")
        }
        return ScenarioExecution(snapshot)
    } finally {
        network.close()
    }
}

private suspend fun failure(
    scenario: GeneratedScenario,
    network: SimulatedNetwork,
    diagnostics: SimulationDiagnostics,
    reason: String,
): SimulationFailure {
    val snapshot = network.snapshot()
    return SimulationFailure(scenario.name, scenario.seed, scenario.topology, scenario.actions,
        scenario.faultPlan, scenario.protocolSeeds, diagnostics.now, diagnostics.processedEvents,
        snapshot.nodes.associate { it.id to (it.state.pendingAdmissions.size + it.state.admittedPackets.size) },
        diagnostics.lastTrace.takeLast(64), reason)
}
