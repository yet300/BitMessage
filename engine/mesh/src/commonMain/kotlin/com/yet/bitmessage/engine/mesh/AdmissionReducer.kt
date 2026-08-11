package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.transport.api.LinkEvent

internal fun reduceLink(
    state: MeshState,
    event: MeshEvent.LinkObserved,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredLink(state, stale = event.generation != state.generation)
    }

    val prepared = state.prepareForCapacity(event.observedAt)
    return when (val linkEvent = event.event) {
        is LinkEvent.Opened -> reduceLinkOpened(prepared, linkEvent, event, limits)
        is LinkEvent.ReadinessChanged -> reduceReadiness(prepared, linkEvent, event)
        is LinkEvent.PayloadReceived -> reducePayload(prepared, linkEvent, event, limits)
        is LinkEvent.Closed -> reduceLinkClosed(prepared, linkEvent, event)
    }
}

internal fun reduceDecoded(
    state: MeshState,
    event: MeshEvent.PacketDecoded,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredDecode(state, event.correlationId, stale = event.generation != state.generation)
    }
    if (!state.wasIssued(event.correlationId, MeshOperation.DECODE_PACKET)) {
        return ignoredDecode(state, event.correlationId, stale = false)
    }
    if (state.pendingAdmissions.containsKey(event.correlationId)) {
        return ignoredDecode(state, event.correlationId, stale = false)
    }

    val prepared = state.prepareForCapacity(event.observedAt)
    val packet = when (val result = event.result) {
        is DecodeResult.Success -> result.value
        is DecodeResult.Failure -> {
            return Transition(
                state = prepared,
                trace = listOf(
                    MeshTrace.record(
                        MeshTraceTransition.DECODE_RESULT,
                        TraceDecision.REJECTED,
                        event.correlationId,
                    ),
                ),
            )
        }
    }

    if (!prepared.links.containsKey(event.source.ingressLink)) {
        return ignoredDecode(prepared, event.correlationId, stale = false)
    }
    val retainedBytes = packet.rawPacket.wireBytes.size
    if (retainedBytes > limits.maxPendingPacketBytes ||
        prepared.pendingAdmissions.size >= limits.maxPendingAdmissions ||
        prepared.pendingAdmissions.values.count { it.source.ingressLink == event.source.ingressLink } >=
        limits.maxPendingAdmissionsPerLink ||
        prepared.aggregatePendingBytes.toLong() + retainedBytes > limits.maxAggregatePendingBytes.toLong()
    ) {
        return limitReached(prepared, event.correlationId, retainedBytes)
    }

    val timeoutTimerId = TimerId.of("${event.correlationId.value}:pending")
    val pending = PendingAdmission(
        source = event.source,
        packet = packet,
        stage = AdmissionStage.AwaitingDigest,
        retainedBytes = retainedBytes,
        expiresAt = event.observedAt.plus(limits.pendingAdmissionLifetime),
        timeoutTimerId = timeoutTimerId,
    )
    val pendingAdmissions = prepared.pendingAdmissions.toMutableMap().apply {
        put(event.correlationId, pending)
    }
    val updated = prepared.copy(
        pendingAdmissions = SnapshotMap(pendingAdmissions),
        aggregatePendingBytes = prepared.aggregatePendingBytes + retainedBytes,
    )
    return Transition(
        state = updated,
        effects = listOf(
            MeshEffect.Schedule(
                correlationId = event.correlationId,
                generation = event.generation,
                timerId = timeoutTimerId,
                delay = limits.pendingAdmissionLifetime,
            ),
            MeshEffect.ComputePacketDigest(
                correlationId = event.correlationId,
                generation = event.generation,
                input = PacketIdentity.input(packet),
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.DECODE_RESULT,
                TraceDecision.APPLIED,
                event.correlationId,
                inputBytes = retainedBytes,
                itemCount = updated.pendingAdmissions.size,
            ),
        ),
    )
}

private fun reduceLinkOpened(
    state: MeshState,
    linkEvent: LinkEvent.Opened,
    event: MeshEvent.LinkObserved,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (!state.links.containsKey(linkEvent.linkId) && state.links.size >= limits.maxActiveLinks) {
        return Transition(
            state = state,
            trace = listOf(
                MeshTrace.record(
                    MeshTraceTransition.LIMIT_REACHED,
                    TraceDecision.REJECTED,
                    itemCount = state.links.size,
                ),
            ),
        )
    }
    val links = state.links.toMutableMap().apply {
        put(linkEvent.linkId, ActiveLink(linkEvent.capabilities, event.observedAt))
    }
    return Transition(
        state = state.copy(links = SnapshotMap(links)),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.LINK_OPENED,
                TraceDecision.APPLIED,
                itemCount = links.size,
            ),
        ),
    )
}

private fun reduceReadiness(
    state: MeshState,
    linkEvent: LinkEvent.ReadinessChanged,
    event: MeshEvent.LinkObserved,
): Transition<MeshState, MeshEffect> {
    if (!state.links.containsKey(linkEvent.linkId)) return ignoredLink(state, stale = false)
    val links = state.links.toMutableMap().apply {
        put(linkEvent.linkId, ActiveLink(linkEvent.capabilities, event.observedAt))
    }
    return Transition(
        state = state.copy(links = SnapshotMap(links)),
        trace = listOf(
            MeshTrace.record(MeshTraceTransition.LINK_READINESS, TraceDecision.APPLIED),
        ),
    )
}

private fun reducePayload(
    state: MeshState,
    linkEvent: LinkEvent.PayloadReceived,
    event: MeshEvent.LinkObserved,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (!state.links.containsKey(linkEvent.linkId)) return ignoredLink(state, stale = false)
    if (linkEvent.bytes.size > limits.maxPendingPacketBytes) {
        return limitReached(state, correlationId = null, inputBytes = linkEvent.bytes.size)
    }

    val issued = state.issueCorrelation(MeshOperation.DECODE_PACKET)
    return Transition(
        state = issued.state,
        effects = listOf(
            MeshEffect.DecodePacket(
                correlationId = issued.correlationId,
                generation = event.generation,
                source = PacketSource.Link(linkEvent.linkId),
                bytes = linkEvent.bytes,
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.PAYLOAD_RECEIVED,
                TraceDecision.APPLIED,
                issued.correlationId,
                inputBytes = linkEvent.bytes.size,
            ),
        ),
    )
}

private fun reduceLinkClosed(
    state: MeshState,
    linkEvent: LinkEvent.Closed,
    event: MeshEvent.LinkObserved,
): Transition<MeshState, MeshEffect> {
    if (!state.links.containsKey(linkEvent.linkId)) return ignoredLink(state, stale = false)

    val removedPending = state.pendingAdmissions
        .filterValues { it.source.ingressLink == linkEvent.linkId }
        .toList()
        .sortedBy { it.first.value }
    val retainedPending = state.pendingAdmissions
        .filterValues { it.source.ingressLink != linkEvent.linkId }
    val links = state.links.toMutableMap().apply { remove(linkEvent.linkId) }
    val bindings = state.provisionalBindings.filterValues { it.linkId != linkEvent.linkId }
    val routes = state.routeObservations.filterValues { it.ingressLink != linkEvent.linkId }
    val effects = removedPending.map { (correlationId, pending) ->
        MeshEffect.Cancel(
            correlationId = correlationId,
            generation = event.generation,
            timerId = pending.timeoutTimerId,
        )
    }
    return Transition(
        state = state.copy(
            links = SnapshotMap(links),
            provisionalBindings = SnapshotMap(bindings),
            pendingAdmissions = SnapshotMap(retainedPending),
            routeObservations = SnapshotMap(routes),
            aggregatePendingBytes = retainedPending.values.sumOf(PendingAdmission::retainedBytes),
        ),
        effects = effects,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.LINK_CLOSED,
                TraceDecision.APPLIED,
                itemCount = links.size,
            ),
        ),
    )
}

private fun MeshState.wasIssued(
    correlationId: CorrelationId,
    operation: MeshOperation,
): Boolean {
    val prefix = "mesh:${generation.value}:${operation.token}:"
    if (!correlationId.value.startsWith(prefix)) return false
    val sequence = correlationId.value.removePrefix(prefix).toLongOrNull() ?: return false
    return sequence >= 0 && sequence < nextCorrelationSequence
}

private fun ignoredLink(
    state: MeshState,
    stale: Boolean,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                if (stale) MeshTraceTransition.STALE_RESULT else MeshTraceTransition.LINK_RESULT,
                TraceDecision.IGNORED,
            ),
        ),
    )

private fun ignoredDecode(
    state: MeshState,
    correlationId: CorrelationId,
    stale: Boolean,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                if (stale) MeshTraceTransition.STALE_RESULT else MeshTraceTransition.DECODE_RESULT,
                TraceDecision.IGNORED,
                correlationId,
            ),
        ),
    )

private fun limitReached(
    state: MeshState,
    correlationId: CorrelationId?,
    inputBytes: Int,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.LIMIT_REACHED,
                TraceDecision.REJECTED,
                correlationId,
                inputBytes = inputBytes,
            ),
        ),
    )
