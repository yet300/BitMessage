package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Engine
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition

class MeshEngine(
    private val limits: MeshLimits = MeshLimits(),
) : Engine<MeshState, MeshEvent, MeshEffect> {
    override fun reduce(
        state: MeshState,
        event: MeshEvent,
    ): Transition<MeshState, MeshEffect> =
        when (event) {
            is MeshEvent.LinkObserved -> reduceLink(state, event, limits)
            is MeshEvent.PacketDecoded -> reduceDecoded(state, event, limits)
            is MeshEvent.PacketDigestComputed -> reduceDigest(state, event, limits)
            is MeshEvent.SignatureVerified -> reduceSignature(state, event, limits)
            is MeshEvent.FragmentPayloadDecoded -> reduceFragmentDecoded(state, event, limits)
            is MeshEvent.RelayEncoded -> reduceRelayEncoded(state, event, limits)
            is MeshEvent.EntropyProvided -> reduceEntropy(state, event, limits)
            is MeshEvent.TimerElapsed -> reduceTimer(state, event)
            is MeshEvent.LinkCompleted -> reduceLinkResult(state, event)
            is MeshEvent.EffectFailed -> reduceEffectFailure(state, event)
            is MeshEvent.RuntimeStarted -> reduceRuntimeStarted(state, event)
            is MeshEvent.RuntimeStopping -> reduceRuntimeStopping(state, event)
        }

    private fun ignoredResult(
        state: MeshState,
        event: MeshAsyncEvent,
    ): Transition<MeshState, MeshEffect> =
        Transition(
            state = state,
            trace = listOf(
                MeshTrace.record(
                    transition = if (event.generation == state.generation) {
                        MeshTraceTransition.EFFECT_FAILURE
                    } else {
                        MeshTraceTransition.STALE_RESULT
                    },
                    decision = TraceDecision.IGNORED,
                    correlationId = event.correlationId,
                ),
            ),
        )

    private fun reduceEffectFailure(
        state: MeshState,
        event: MeshEvent.EffectFailed,
    ): Transition<MeshState, MeshEffect> {
        if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
            return ignoredResult(state, event)
        }
        val pending = state.pendingLinkWrites[event.correlationId] ?: return ignoredResult(state, event)
        return Transition(
            state = state.copy(
                observedAt = event.observedAt,
                pendingLinkWrites = SnapshotMap(state.pendingLinkWrites - event.correlationId),
            ),
            effects = listOf(MeshEffect.Cancel(event.correlationId, event.generation, pending.timeoutTimerId)),
            trace = listOf(MeshTrace.record(MeshTraceTransition.EFFECT_FAILURE, TraceDecision.REJECTED, event.correlationId)),
        )
    }

    private fun reduceRuntimeStarted(
        state: MeshState,
        event: MeshEvent.RuntimeStarted,
    ): Transition<MeshState, MeshEffect> {
        if (event.generation != state.generation) return staleLifecycle(state)
        val running = state.prepareForCapacity(event.observedAt).copy(
            localPeer = event.localPeer,
            lifecycle = MeshLifecycle.RUNNING,
            dedupExpiryTimer = null,
            topologyExpiryTimer = null,
        )
        val dedup = scheduleDedupExpiry(running, event.observedAt, cancelExisting = false)
        return Transition(
            state = dedup.state,
            effects = dedup.effects,
            trace = listOf(
                MeshTrace.record(MeshTraceTransition.LIFECYCLE, TraceDecision.APPLIED),
            ),
        )
    }

    private fun reduceRuntimeStopping(
        state: MeshState,
        event: MeshEvent.RuntimeStopping,
    ): Transition<MeshState, MeshEffect> {
        if (event.generation != state.generation) return staleLifecycle(state)
        val prepared = state.prepareForCapacity(event.observedAt)
        return Transition(
            state = prepared.copy(
                lifecycle = MeshLifecycle.STOPPED,
                links = SnapshotMap(),
                provisionalBindings = SnapshotMap(),
                pendingAdmissions = SnapshotMap(),
                pendingFragmentDecodes = SnapshotMap(),
                fragmentStreams = SnapshotMap(),
                routeObservations = SnapshotMap(),
                scheduledRelays = SnapshotMap(),
                pendingRelayEntropy = SnapshotMap(),
                pendingRelayEncodes = SnapshotMap(),
                pendingLinkWrites = SnapshotMap(),
                dedupExpiryTimer = null,
                topologyExpiryTimer = null,
                aggregatePendingBytes = 0,
                aggregateFragmentBytes = 0,
            ),
            trace = listOf(
                MeshTrace.record(MeshTraceTransition.LIFECYCLE, TraceDecision.APPLIED),
            ),
        )
    }

    private fun staleLifecycle(state: MeshState): Transition<MeshState, MeshEffect> =
        Transition(
            state = state,
            trace = listOf(
                MeshTrace.record(MeshTraceTransition.STALE_RESULT, TraceDecision.IGNORED),
            ),
        )
}
