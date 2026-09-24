package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.transport.api.LinkCommand
import com.yet.bitmessage.transport.api.LinkResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal object RelayPolicy {
    /**
     * A conservative BitMessage-local resource policy, not a cross-client compatibility constant.
     * Canonical evidence proves the concrete upstream-compatible 7 -> 6 relay mutation only.
     */
    const val LOCAL_MAX_RECEIVED_TTL: Int = 7

    fun outgoingTtl(received: UByte): UByte? {
        val capped = minOf(received.toInt(), LOCAL_MAX_RECEIVED_TTL)
        return if (capped < MIN_RELAY_INPUT_TTL) null else (capped - 1).toUByte()
    }

    fun relayDelay(bytes: Bytes): Duration {
        require(bytes.size == RELAY_ENTROPY_BYTES) { "Relay jitter requires exactly two bytes." }
        val unsigned = ((bytes[0].toInt() and 0xff) shl Byte.SIZE_BITS) or
            (bytes[1].toInt() and 0xff)
        return (unsigned % RELAY_DELAY_VALUES).milliseconds
    }

    fun selectTargets(
        state: MeshState,
        packet: DecodedPacket,
        ingress: LinkId,
        encodedSize: Int,
        limits: MeshLimits,
    ): List<LinkId> {
        require(encodedSize >= 0) { "Encoded relay size must not be negative." }
        val route = packet.route
        if (route != null) {
            val localIndexes = route.entries.mapIndexedNotNull { index, peer ->
                index.takeIf { peer == state.localPeer }
            }
            if (localIndexes.size > 1) return emptyList()
            val localIndex = localIndexes.singleOrNull()
            if (localIndex != null && localIndex + 1 < route.entries.size) {
                val nextPeer = route.entries[localIndex + 1]
                val direct = state.provisionalBindings.values
                    .asSequence()
                    .filter { it.peer == nextPeer && it.expiresAt > state.observedAt }
                    .map(PeerBinding::linkId)
                    .distinct()
                    .filter { it != ingress && isEligible(state, it, encodedSize) }
                    .toList()
                if (direct.size == 1) return direct
            }
        }

        return state.links.keys
            .asSequence()
            .filter { it != ingress && isEligible(state, it, encodedSize) }
            .sortedBy(LinkId::value)
            .take(limits.maxRelayFanout)
            .toList()
    }

    fun isEligible(state: MeshState, linkId: LinkId, encodedSize: Int): Boolean {
        val link = state.links[linkId] ?: return false
        return link.capabilities.writeReady && link.capabilities.maxWriteBytes >= encodedSize
    }

    private const val MIN_RELAY_INPUT_TTL: Int = 2
    private const val RELAY_ENTROPY_BYTES: Int = 2
    private const val RELAY_DELAY_VALUES: Int = 501
}

internal fun reduceEntropy(
    state: MeshState,
    event: MeshEvent.EntropyProvided,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredRelay(state, event.correlationId, stale = event.generation != state.generation)
    }
    val prepared = state.prepareForCapacity(event.observedAt)
    val request = prepared.pendingRelayEntropy[event.correlationId]
    if (request == null ||
        request.packetId != event.packetId ||
        request.source != event.source ||
        request.packet != event.packet ||
        request.outgoingTtl != event.outgoingTtl ||
        !prepared.admittedPackets.containsKey(request.packetId) ||
        prepared.scheduledRelays.containsKey(request.packetId)
    ) {
        return ignoredRelay(prepared, event.correlationId, stale = false)
    }
    val remainingRequests = prepared.pendingRelayEntropy.toMutableMap().apply {
        remove(event.correlationId)
    }
    val withoutRequest = prepared.copy(pendingRelayEntropy = SnapshotMap(remainingRequests))
    val entropy = when (val result = event.result) {
        is MeshResult.Success -> result.value
        is MeshResult.Failure -> return rejectedRelay(withoutRequest, event.correlationId)
    }
    if (entropy.size != RELAY_ENTROPY_BYTES) {
        return rejectedRelay(withoutRequest, event.correlationId)
    }

    if (withoutRequest.scheduledRelays.size >= limits.maxScheduledRelays ||
        withoutRequest.scheduledRelays.values.count { it.sourcePeer == request.packet.sender } >=
        limits.maxScheduledRelaysPerSource
    ) {
        return rejectedRelay(withoutRequest, event.correlationId)
    }
    val targets = RelayPolicy.selectTargets(
        state = withoutRequest,
        packet = request.packet,
        ingress = request.source.ingressLink,
        encodedSize = request.packet.rawPacket.wireBytes.size,
        limits = limits,
    )
    if (targets.isEmpty()) return rejectedRelay(withoutRequest, event.correlationId)

    val delay = RelayPolicy.relayDelay(entropy)
    val issued = withoutRequest.issueCorrelation(MeshOperation.TIMER)
    val timerId = TimerId.of("${issued.correlationId.value}:relay")
    val relay = ScheduledRelay(
        packetId = request.packetId,
        sourcePeer = request.packet.sender,
        ingressLink = request.source.ingressLink,
        packet = request.packet,
        targets = SnapshotList(targets),
        outgoingTtl = request.outgoingTtl,
        correlationId = issued.correlationId,
        timerId = timerId,
        dueAt = event.observedAt.plus(delay),
        validUntil = requireNotNull(issued.state.admittedPackets[request.packetId]).expiresAt,
    )
    val relays = issued.state.scheduledRelays.toMutableMap().apply {
        put(request.packetId, relay)
    }
    return Transition(
        state = issued.state.copy(scheduledRelays = SnapshotMap(relays)),
        effects = listOf(
            MeshEffect.Schedule(
                correlationId = issued.correlationId,
                generation = event.generation,
                timerId = timerId,
                delay = delay,
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.RELAY,
                TraceDecision.SCHEDULED,
                issued.correlationId,
                itemCount = targets.size,
            ),
        ),
    )
}

internal fun reduceRelayTimerOrNull(
    state: MeshState,
    event: MeshEvent.TimerElapsed,
): Transition<MeshState, MeshEffect>? {
    val relay = state.scheduledRelays.values.firstOrNull {
        it.correlationId == event.correlationId && it.timerId == event.timerId
    } ?: return null
    if (event.observedAt < relay.dueAt) {
        return ignoredRelay(state, event.correlationId, stale = false)
    }
    if (event.observedAt >= relay.validUntil) {
        return ignoredRelay(
            state.copy(scheduledRelays = SnapshotMap(state.scheduledRelays - relay.packetId)),
            event.correlationId,
            stale = false,
        )
    }

    val relays = state.scheduledRelays.toMutableMap().apply { remove(relay.packetId) }
    val issued = state.copy(
        observedAt = event.observedAt,
        scheduledRelays = SnapshotMap(relays),
    )
        .issueCorrelation(MeshOperation.ENCODE_RELAY)
    val pendingEncodes = issued.state.pendingRelayEncodes.toMutableMap().apply {
        put(
            issued.correlationId,
            PendingRelayEncode(
                packetId = relay.packetId,
                sourcePeer = relay.sourcePeer,
                packet = relay.packet,
                targets = relay.targets,
                expiresAt = relay.validUntil,
            ),
        )
    }
    return Transition(
        state = issued.state.copy(pendingRelayEncodes = SnapshotMap(pendingEncodes)),
        effects = listOf(
            MeshEffect.EncodeRelay(
                correlationId = issued.correlationId,
                generation = state.generation,
                packetId = relay.packetId,
                packet = relay.packet,
                outgoingTtl = relay.outgoingTtl,
                targets = relay.targets,
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.RELAY,
                TraceDecision.APPLIED,
                issued.correlationId,
                itemCount = relay.targets.size,
            ),
        ),
    )
}

internal fun reduceRelayEncoded(
    state: MeshState,
    event: MeshEvent.RelayEncoded,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredRelay(state, event.correlationId, stale = event.generation != state.generation)
    }
    val prepared = state.prepareForCapacity(event.observedAt)
    val request = prepared.pendingRelayEncodes[event.correlationId]
    if (request == null || request.packetId != event.packetId || request.targets != event.targets) {
        return ignoredRelay(prepared, event.correlationId, stale = false)
    }
    val remainingEncodes = prepared.pendingRelayEncodes.toMutableMap().apply {
        remove(event.correlationId)
    }
    val withoutEncode = prepared.copy(pendingRelayEncodes = SnapshotMap(remainingEncodes))
    val bytes = when (val result = event.result) {
        is EncodeResult.Success -> result.bytes
        is EncodeResult.Failure -> return rejectedRelay(withoutEncode, event.correlationId)
    }

    var updated = withoutEncode
    val effects = mutableListOf<MeshEffect>()
    event.targets.forEach { linkId ->
        if (RelayPolicy.isEligible(updated, linkId, bytes.size) &&
            updated.pendingLinkWrites.values.none { it.linkId == linkId }
        ) {
            val issued = updated.issueCorrelation(MeshOperation.LINK_WRITE)
            val timeoutTimerId = TimerId.of("${issued.correlationId.value}:write-timeout")
            val pendingWrites = issued.state.pendingLinkWrites.toMutableMap().apply {
                put(issued.correlationId, PendingLinkWrite(linkId, timeoutTimerId, event.observedAt.plus(limits.linkWriteLifetime)))
            }
            updated = issued.state.copy(pendingLinkWrites = SnapshotMap(pendingWrites))
            effects += MeshEffect.Schedule(issued.correlationId, state.generation, timeoutTimerId, limits.linkWriteLifetime)
            effects += MeshEffect.WriteLink(
                LinkCommand.Write(
                    linkId = linkId,
                    correlationId = issued.correlationId,
                    generation = state.generation,
                    bytes = bytes,
                ),
            )
        }
    }
    return Transition(
        state = updated,
        effects = effects,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.RELAY,
                if (effects.isEmpty()) TraceDecision.REJECTED else TraceDecision.APPLIED,
                event.correlationId,
                outputBytes = bytes.size,
                itemCount = effects.size,
            ),
        ),
    )
}

internal fun reduceLinkResult(
    state: MeshState,
    event: MeshEvent.LinkCompleted,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredRelay(state, event.correlationId, stale = event.generation != state.generation)
    }
    val pendingLink = state.pendingLinkWrites[event.correlationId]
    if (pendingLink == null || pendingLink.linkId != event.result.linkId) {
        return ignoredRelay(state, event.correlationId, stale = false)
    }
    val pendingWrites = state.pendingLinkWrites.toMutableMap().apply {
        remove(event.correlationId)
    }
    val decision = when (event.result) {
        is LinkResult.Written -> TraceDecision.APPLIED
        is LinkResult.Backpressured,
        is LinkResult.PayloadTooLarge,
        is LinkResult.Disconnected,
        is LinkResult.Unsupported,
        is LinkResult.Failed,
        -> TraceDecision.REJECTED
    }
    return Transition(
        state = state.copy(
            observedAt = event.observedAt,
            pendingLinkWrites = SnapshotMap(pendingWrites),
        ),
        effects = listOf(MeshEffect.Cancel(event.correlationId, event.generation, pendingLink.timeoutTimerId)),
        trace = listOf(
            MeshTrace.record(MeshTraceTransition.LINK_RESULT, decision, event.correlationId),
        ),
    )
}

private fun ignoredRelay(
    state: MeshState,
    correlationId: com.yet.bitmessage.foundation.CorrelationId,
    stale: Boolean,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                if (stale) MeshTraceTransition.STALE_RESULT else MeshTraceTransition.RELAY,
                TraceDecision.IGNORED,
                correlationId,
            ),
        ),
    )

private fun rejectedRelay(
    state: MeshState,
    correlationId: com.yet.bitmessage.foundation.CorrelationId,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.RELAY,
                TraceDecision.REJECTED,
                correlationId,
            ),
        ),
    )

private const val RELAY_ENTROPY_BYTES: Int = 2
