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
            is MeshEvent.RelayEncoded -> reduceRelayEncoded(state, event)
            is MeshEvent.EntropyProvided -> reduceEntropy(state, event, limits)
            is MeshEvent.TimerElapsed -> reduceTimer(state, event)
            is MeshEvent.LinkCompleted -> reduceLinkResult(state, event)
            is MeshEvent.EffectFailed -> ignoredResult(state, event)
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

    private fun reduceRuntimeStarted(
        state: MeshState,
        event: MeshEvent.RuntimeStarted,
    ): Transition<MeshState, MeshEffect> {
        if (event.generation != state.generation) return staleLifecycle(state)
        return Transition(
            state = state.copy(
                localPeer = event.localPeer,
                observedAt = event.observedAt,
                lifecycle = MeshLifecycle.RUNNING,
            ),
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
        return Transition(
            state = state.copy(
                observedAt = event.observedAt,
                lifecycle = MeshLifecycle.STOPPING,
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
