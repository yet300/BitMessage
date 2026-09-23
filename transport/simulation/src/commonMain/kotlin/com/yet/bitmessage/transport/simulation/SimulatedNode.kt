package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEngine
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshState
import com.yet.bitmessage.engine.mesh.runtime.MeshRuntime
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerDriverFactory
import com.yet.bitmessage.engine.mesh.runtime.StartResult
import com.yet.bitmessage.engine.mesh.runtime.StopResult
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.CoroutineScope

data class SimulatedNodeConfig(
    val id: SimulatedNodeId,
    val localPeer: WirePeerId,
    val protocolSeed: Int,
    val meshLimits: MeshLimits = MeshLimits(),
    val verificationPlan: VerificationPlan = VerificationPlan.EMPTY,
    val digestPlan: DigestPlan = DigestPlan.EMPTY,
)

/** The publication record deliberately contains no payload or wire bytes. */
data class PublicationProjection(
    val nodeId: SimulatedNodeId,
    val packetId: PacketId,
    val sender: WirePeerId,
    val timestamp: ULong,
    val payloadSize: Int,
)

data class SimulatedNodeSnapshot(
    val id: SimulatedNodeId,
    val state: MeshState,
    val publications: List<PublicationProjection>,
    val entropyTranscript: List<ProtocolEntropyRecord>,
    val structuralDecodeRejections: Long,
)

class SimulationLimitExceededException(message: String) : IllegalStateException(message)

internal class BoundedPublications(private val capacity: Int) {
    init {
        require(capacity > 0) { "Publication capacity must be positive." }
    }

    private val retained = mutableListOf<PublicationProjection>()
    private var overflow: SimulationLimitExceededException? = null

    fun append(record: PublicationProjection) {
        overflow?.let { throw it }
        if (retained.size >= capacity) {
            val failure = SimulationLimitExceededException("Publication record limit $capacity exceeded.")
            overflow = failure
            throw failure
        }
        retained += record
    }

    fun snapshot(): List<PublicationProjection> {
        overflow?.let { throw it }
        return retained.toList()
    }
}

/** One real mesh reducer/runtime per simulated node; the network owns its lifecycle. */
class SimulatedNode internal constructor(
    val config: SimulatedNodeConfig,
    parentScope: CoroutineScope,
    host: SimulationEffectHost,
    timerDriverFactory: MeshTimerDriverFactory,
    maximumPublications: Int,
) {
    private val publications = BoundedPublications(maximumPublications)
    private val entropy = NodeProtocolEntropy(config.protocolSeed)
    private val executor = SimulationEffectExecutor(
        config.id,
        host,
        entropy,
        config.verificationPlan,
        config.digestPlan,
    )

    val runtime = MeshRuntime(
        MeshEngine(config.meshLimits),
        executor,
        parentScope,
        config.meshLimits,
        timerDriverFactory,
    )

    suspend fun start(at: MonotonicTime): StartResult = runtime.start(config.localPeer, at)

    suspend fun stop(at: MonotonicTime): StopResult = runtime.stop(at)

    suspend fun close(at: MonotonicTime) = runtime.close(at)

    internal fun record(effect: MeshEffect.PublishPublicPayload) {
        publications.append(
            PublicationProjection(
                nodeId = config.id,
                packetId = effect.packetId,
                sender = effect.sender,
                timestamp = effect.timestamp,
                payloadSize = effect.payload.size,
            ),
        )
    }

    fun snapshot(): SimulatedNodeSnapshot = SimulatedNodeSnapshot(
        id = config.id,
        state = requireNotNull(runtime.state.value) { "Simulated node has not started." },
        publications = publications.snapshot(),
        entropyTranscript = entropy.transcript,
        structuralDecodeRejections = runtime.structuralDecodeRejectionCount.value,
    )
}
