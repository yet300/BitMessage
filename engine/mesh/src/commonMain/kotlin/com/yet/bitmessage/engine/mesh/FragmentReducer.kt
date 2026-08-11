package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.FragmentPayload

internal fun reduceFragmentDecoded(
    state: MeshState,
    event: MeshEvent.FragmentPayloadDecoded,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredFragment(state, event.correlationId, stale = event.generation != state.generation)
    }
    val prepared = state.prepareForCapacity(event.observedAt)
    val request = prepared.pendingFragmentDecodes[event.correlationId]
    if (request == null ||
        request.packetId != event.packetId ||
        request.source != event.source ||
        request.sender != event.sender
    ) {
        return ignoredFragment(prepared, event.correlationId, stale = false)
    }
    val remainingRequests = prepared.pendingFragmentDecodes.toMutableMap().apply {
        remove(event.correlationId)
    }
    val withoutRequest = prepared.copy(
        pendingFragmentDecodes = SnapshotMap(remainingRequests),
    )
    val fragment = when (val result = event.result) {
        is DecodeResult.Success -> result.value
        is DecodeResult.Failure -> return rejectedFragment(withoutRequest, event.correlationId)
    }
    val key = FragmentStreamKey(request.sender, fragment.id)
    val existing = withoutRequest.fragmentStreams[key]
    if (existing != null &&
        (existing.total != fragment.total || existing.originalType != fragment.originalType)
    ) {
        return rejectFragmentConflict(withoutRequest, event.correlationId, key, existing)
    }
    if (fragment.total.toInt() > limits.maxFragmentsPerStream) {
        return rejectedFragment(withoutRequest, event.correlationId)
    }
    if (existing == null) {
        return startFragmentStream(withoutRequest, event, request, fragment, key, limits)
    }

    val retainedAtIndex = existing.fragments[fragment.index]
    if (retainedAtIndex != null) {
        return if (retainedAtIndex == fragment.data) {
            ignoredFragment(withoutRequest, event.correlationId, stale = false)
        } else {
            rejectFragmentConflict(withoutRequest, event.correlationId, key, existing)
        }
    }

    val newStreamBytes = checkedAddition(existing.retainedBytes, fragment.data.size)
        ?: return rejectedFragment(withoutRequest, event.correlationId)
    val newAggregateBytes = checkedAddition(withoutRequest.aggregateFragmentBytes, fragment.data.size)
        ?: return rejectedFragment(withoutRequest, event.correlationId)
    if (newStreamBytes > limits.maxFragmentStreamBytes ||
        newAggregateBytes > limits.maxAggregateFragmentBytes
    ) {
        return rejectedFragment(withoutRequest, event.correlationId)
    }

    val fragments = existing.fragments.toMutableMap().apply {
        put(fragment.index, fragment.data)
    }
    if (fragments.size == fragment.total.toInt()) {
        return completeFragmentStream(
            state = withoutRequest,
            event = event,
            key = key,
            stream = existing,
            fragments = fragments,
            retainedBytes = newStreamBytes,
        )
    }

    val streams = withoutRequest.fragmentStreams.toMutableMap().apply {
        put(
            key,
            existing.copy(
                fragments = SnapshotMap(fragments),
                retainedBytes = newStreamBytes,
            ),
        )
    }
    return Transition(
        state = withoutRequest.copy(
            fragmentStreams = SnapshotMap(streams),
            aggregateFragmentBytes = newAggregateBytes,
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.FRAGMENT,
                TraceDecision.APPLIED,
                event.correlationId,
                inputBytes = fragment.data.size,
                itemCount = fragments.size,
            ),
        ),
    )
}

internal fun reduceFragmentTimerOrNull(
    state: MeshState,
    event: MeshEvent.TimerElapsed,
): Transition<MeshState, MeshEffect>? {
    val entry = state.fragmentStreams.entries.firstOrNull { (_, stream) ->
        stream.timerCorrelationId == event.correlationId && stream.timerId == event.timerId
    } ?: return null
    val (key, stream) = entry
    if (event.observedAt < stream.expiresAt) {
        return ignoredFragment(state, event.correlationId, stale = false)
    }

    val streams = state.fragmentStreams.toMutableMap().apply { remove(key) }
    return Transition(
        state = state.copy(
            observedAt = event.observedAt,
            fragmentStreams = SnapshotMap(streams),
            aggregateFragmentBytes = state.aggregateFragmentBytes - stream.retainedBytes,
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.TIMER,
                TraceDecision.APPLIED,
                event.correlationId,
                itemCount = streams.size,
            ),
        ),
    )
}

private fun startFragmentStream(
    state: MeshState,
    event: MeshEvent.FragmentPayloadDecoded,
    request: PendingFragmentDecode,
    fragment: FragmentPayload,
    key: FragmentStreamKey,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (fragment.data.size > limits.maxFragmentStreamBytes) {
        return rejectedFragment(state, event.correlationId)
    }
    if (fragment.total == 1.toUShort()) {
        val issued = state.issueCorrelation(MeshOperation.DECODE_PACKET)
        return Transition(
            state = issued.state,
            effects = listOf(
                MeshEffect.DecodePacket(
                    correlationId = issued.correlationId,
                    generation = event.generation,
                    source = PacketSource.Reassembled(request.source.ingressLink, fragment.id),
                    bytes = fragment.data,
                ),
            ),
            trace = listOf(
                MeshTrace.record(
                    MeshTraceTransition.FRAGMENT,
                    TraceDecision.APPLIED,
                    event.correlationId,
                    outputBytes = fragment.data.size,
                    itemCount = 1,
                ),
            ),
        )
    }
    if (state.fragmentStreams.size >= limits.maxFragmentStreams ||
        state.fragmentStreams.keys.count { it.sourcePeer == request.sender } >=
        limits.maxFragmentStreamsPerSource
    ) {
        return rejectedFragment(state, event.correlationId)
    }
    val newAggregateBytes = checkedAddition(state.aggregateFragmentBytes, fragment.data.size)
        ?: return rejectedFragment(state, event.correlationId)
    if (newAggregateBytes > limits.maxAggregateFragmentBytes) {
        return rejectedFragment(state, event.correlationId)
    }

    val issued = state.issueCorrelation(MeshOperation.TIMER)
    val timerId = TimerId.of("${issued.correlationId.value}:fragment")
    val stream = FragmentStream(
        ingressLink = request.source.ingressLink,
        total = fragment.total,
        originalType = fragment.originalType,
        fragments = SnapshotMap(mapOf(fragment.index to fragment.data)),
        retainedBytes = fragment.data.size,
        expiresAt = event.observedAt.plus(limits.fragmentLifetime),
        timerCorrelationId = issued.correlationId,
        timerId = timerId,
    )
    val streams = issued.state.fragmentStreams.toMutableMap().apply { put(key, stream) }
    return Transition(
        state = issued.state.copy(
            fragmentStreams = SnapshotMap(streams),
            aggregateFragmentBytes = newAggregateBytes,
        ),
        effects = listOf(
            MeshEffect.Schedule(
                correlationId = issued.correlationId,
                generation = event.generation,
                timerId = timerId,
                delay = limits.fragmentLifetime,
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.FRAGMENT,
                TraceDecision.SCHEDULED,
                event.correlationId,
                inputBytes = fragment.data.size,
                itemCount = 1,
            ),
        ),
    )
}

private fun completeFragmentStream(
    state: MeshState,
    event: MeshEvent.FragmentPayloadDecoded,
    key: FragmentStreamKey,
    stream: FragmentStream,
    fragments: Map<UShort, Bytes>,
    retainedBytes: Int,
): Transition<MeshState, MeshEffect> {
    val joined = joinFragments(fragments, stream.total, retainedBytes)
        ?: return rejectedFragment(state, event.correlationId)
    val streams = state.fragmentStreams.toMutableMap().apply { remove(key) }
    val withoutStream = state.copy(
        fragmentStreams = SnapshotMap(streams),
        aggregateFragmentBytes = state.aggregateFragmentBytes - stream.retainedBytes,
    )
    val issued = withoutStream.issueCorrelation(MeshOperation.DECODE_PACKET)
    return Transition(
        state = issued.state,
        effects = listOf(
            MeshEffect.Cancel(
                correlationId = stream.timerCorrelationId,
                generation = event.generation,
                timerId = stream.timerId,
            ),
            MeshEffect.DecodePacket(
                correlationId = issued.correlationId,
                generation = event.generation,
                source = PacketSource.Reassembled(stream.ingressLink, key.fragmentId),
                bytes = joined,
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.FRAGMENT,
                TraceDecision.APPLIED,
                event.correlationId,
                outputBytes = joined.size,
                itemCount = fragments.size,
            ),
        ),
    )
}

private fun rejectFragmentConflict(
    state: MeshState,
    correlationId: CorrelationId,
    key: FragmentStreamKey,
    stream: FragmentStream,
): Transition<MeshState, MeshEffect> {
    val streams = state.fragmentStreams.toMutableMap().apply { remove(key) }
    return Transition(
        state = state.copy(
            fragmentStreams = SnapshotMap(streams),
            aggregateFragmentBytes = state.aggregateFragmentBytes - stream.retainedBytes,
        ),
        effects = listOf(
            MeshEffect.Cancel(
                correlationId = stream.timerCorrelationId,
                generation = state.generation,
                timerId = stream.timerId,
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.FRAGMENT,
                TraceDecision.REJECTED,
                correlationId,
            ),
        ),
    )
}

private fun joinFragments(
    fragments: Map<UShort, Bytes>,
    total: UShort,
    retainedBytes: Int,
): Bytes? {
    val output = ByteArray(retainedBytes)
    var offset = 0
    repeat(total.toInt()) { rawIndex ->
        val bytes = fragments[rawIndex.toUShort()] ?: return null
        val source = bytes.copyToByteArray()
        if (offset > output.size - source.size) return null
        source.copyInto(output, destinationOffset = offset)
        offset += source.size
    }
    return if (offset == output.size) Bytes.copyOf(output) else null
}

private fun checkedAddition(left: Int, right: Int): Int? {
    if (left < 0 || right < 0) return null
    val total = left.toLong() + right.toLong()
    if (total > Int.MAX_VALUE) return null
    return total.toInt()
}

private fun ignoredFragment(
    state: MeshState,
    correlationId: CorrelationId,
    stale: Boolean,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                if (stale) MeshTraceTransition.STALE_RESULT else MeshTraceTransition.FRAGMENT,
                TraceDecision.IGNORED,
                correlationId,
            ),
        ),
    )

private fun rejectedFragment(
    state: MeshState,
    correlationId: CorrelationId,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.FRAGMENT,
                TraceDecision.REJECTED,
                correlationId,
            ),
        ),
    )
