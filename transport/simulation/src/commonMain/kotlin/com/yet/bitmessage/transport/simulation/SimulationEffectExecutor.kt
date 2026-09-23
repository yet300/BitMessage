package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshResult
import com.yet.bitmessage.engine.mesh.runtime.MeshEffectExecutor
import com.yet.bitmessage.foundation.EntropyRequest
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.transport.api.LinkCommand

/** Network operations are committed by the host or throw; none may be silently discarded. */
internal interface SimulationEffectHost {
    val now: MonotonicTime

    suspend fun scheduleMeshEvent(nodeId: SimulatedNodeId, deadline: MonotonicTime, event: MeshEvent)

    suspend fun submitWrite(nodeId: SimulatedNodeId, command: LinkCommand.Write)

    suspend fun submitClose(nodeId: SimulatedNodeId, command: LinkCommand.Close)

    fun recordPublication(nodeId: SimulatedNodeId, effect: MeshEffect.PublishPublicPayload)
}

internal class SimulationEffectExecutor(
    private val nodeId: SimulatedNodeId,
    private val host: SimulationEffectHost,
    private val entropy: NodeProtocolEntropy,
    private val verificationPlan: VerificationPlan,
    private val digestPlan: DigestPlan,
) : MeshEffectExecutor {
    private var digestOrdinal: Long = 0
    private var verificationOrdinal: Long = 0

    override suspend fun execute(effect: MeshEffect): MeshEvent? = when (effect) {
        is MeshEffect.ComputePacketDigest -> {
            digestOrdinal = nextOrdinal(digestOrdinal)
            val outcome = digestPlan.outcome(digestOrdinal, effect.input)
            deliver(outcome) { observedAt, result ->
                MeshEvent.PacketDigestComputed(
                    digestPlan.eventCorrelationId(digestOrdinal, effect.correlationId),
                    digestPlan.eventGeneration(digestOrdinal, effect.generation),
                    observedAt,
                    result,
                )
            }
        }
        is MeshEffect.VerifySignature -> {
            verificationOrdinal = nextOrdinal(verificationOrdinal)
            val outcome = verificationPlan.outcome(verificationOrdinal)
            deliver(outcome) { observedAt, result ->
                MeshEvent.SignatureVerified(effect.correlationId, effect.generation, observedAt, result)
            }
        }
        is MeshEffect.DecodeFragmentPayload -> MeshEvent.FragmentPayloadDecoded(
            correlationId = effect.correlationId,
            generation = effect.generation,
            observedAt = host.now,
            packetId = effect.packetId,
            source = effect.source,
            sender = effect.sender,
            result = FragmentPayloadCodec.decode(effect.payload),
        )
        is MeshEffect.EncodeRelay -> MeshEvent.RelayEncoded(
            correlationId = effect.correlationId,
            generation = effect.generation,
            observedAt = host.now,
            packetId = effect.packetId,
            targets = effect.targets,
            result = RelayEncoding.withTtl(effect.packet, effect.outgoingTtl),
        )
        is MeshEffect.RequestEntropy -> MeshEvent.EntropyProvided(
            correlationId = effect.correlationId,
            generation = effect.generation,
            observedAt = host.now,
            packetId = effect.packetId,
            source = effect.source,
            packet = effect.packet,
            outgoingTtl = effect.outgoingTtl,
            result = MeshResult.Success(
                entropy.generate(EntropyRequest(effect.correlationId, effect.byteCount)).bytes,
            ),
        )
        is MeshEffect.WriteLink -> {
            host.submitWrite(nodeId, effect.command)
            null
        }
        is MeshEffect.CloseLink -> {
            host.submitClose(nodeId, effect.command)
            null
        }
        is MeshEffect.PublishPublicPayload -> {
            host.recordPublication(nodeId, effect)
            null
        }
        is MeshEffect.ReinjectPacket,
        is MeshEffect.Schedule,
        is MeshEffect.Cancel,
        -> error("Runtime-owned effect reached simulation executor: ${effect::class.simpleName}")
    }

    private suspend fun <T> deliver(
        outcome: PlannedOutcome<T>,
        eventAt: (MonotonicTime, MeshResult<T>) -> MeshEvent,
    ): MeshEvent? = when (outcome) {
        is PlannedOutcome.Immediate -> eventAt(host.now, outcome.result)
        is PlannedOutcome.Delayed -> {
            val deadline = host.now.plus(outcome.delay)
            host.scheduleMeshEvent(nodeId, deadline, eventAt(deadline, outcome.result))
            null
        }
    }

    private fun nextOrdinal(current: Long): Long {
        check(current < Long.MAX_VALUE) { "Protocol effect request ordinal exhausted." }
        return current + 1
    }
}
