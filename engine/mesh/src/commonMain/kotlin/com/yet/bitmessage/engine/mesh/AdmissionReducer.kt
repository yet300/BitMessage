package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.BitchatBaseline2026_08
import com.yet.bitmessage.protocol.bitchat.PacketAdmissionPolicy
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.KnownPacketType
import com.yet.bitmessage.protocol.bitchat.admissionPolicy
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
        is LinkEvent.PayloadReceived -> error("PayloadReceived must be decoded by MeshProtocolAdapter.")
        is LinkEvent.Closed -> reduceLinkClosed(prepared, linkEvent, event)
    }
}

internal fun reduceDecoded(
    state: MeshState,
    event: MeshEvent.PacketDecoded,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredPacketIngress(state, stale = event.generation != state.generation)
    }

    if (!state.links.containsKey(event.source.ingressLink)) {
        return ignoredPacketIngress(state, stale = false)
    }
    val prepared = state.prepareForCapacity(event.observedAt)
    val packet = event.packet
    val retainedBytes = packet.rawPacket.wireBytes.size
    if (retainedBytes > limits.maxPendingPacketBytes ||
        prepared.pendingAdmissions.size >= limits.maxPendingAdmissions ||
        prepared.pendingAdmissions.values.count { it.source.ingressLink == event.source.ingressLink } >=
        limits.maxPendingAdmissionsPerLink ||
        prepared.aggregatePendingBytes.toLong() + retainedBytes > limits.maxAggregatePendingBytes.toLong() ||
        prepared.aggregateRetainedPacketBytes.toLong() + retainedBytes > limits.maxAggregateRetainedPacketBytes.toLong()
    ) {
        return limitReached(prepared, correlationId = null, inputBytes = retainedBytes)
    }

    val issued = prepared.issueCorrelation(MeshOperation.COMPUTE_DIGEST)
    val timeoutTimerId = TimerId.of("${issued.correlationId.value}:pending")
    val pending = PendingAdmission(
        source = event.source,
        packet = packet,
        signingTranscript = event.signingTranscript,
        stage = AdmissionStage.AwaitingDigest,
        retainedBytes = retainedBytes,
        expiresAt = event.observedAt.plus(limits.pendingAdmissionLifetime),
        timeoutTimerId = timeoutTimerId,
    )
    val pendingAdmissions = issued.state.pendingAdmissions.toMutableMap().apply {
        put(issued.correlationId, pending)
    }
    val updated = issued.state.copy(
        pendingAdmissions = SnapshotMap(pendingAdmissions),
        aggregatePendingBytes = issued.state.aggregatePendingBytes + retainedBytes,
    )
    return Transition(
        state = updated,
        effects = listOf(
            MeshEffect.Schedule(
                correlationId = issued.correlationId,
                generation = event.generation,
                timerId = timeoutTimerId,
                delay = limits.pendingAdmissionLifetime,
            ),
            MeshEffect.ComputePacketDigest(
                correlationId = issued.correlationId,
                generation = event.generation,
                input = PacketIdentity.input(packet),
            ),
        ),
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.DECODE_RESULT,
                TraceDecision.APPLIED,
                issued.correlationId,
                inputBytes = retainedBytes,
                itemCount = updated.pendingAdmissions.size,
            ),
        ),
    )
}

internal fun reduceDigest(
    state: MeshState,
    event: MeshEvent.PacketDigestComputed,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredAdmission(state, event.correlationId, stale = event.generation != state.generation)
    }
    val prepared = state.prepareForCapacity(event.observedAt)
    val pending = prepared.pendingAdmissions[event.correlationId]
        ?: return ignoredAdmission(state, event.correlationId, stale = false)
    if (pending.stage != AdmissionStage.AwaitingDigest) {
        return ignoredAdmission(state, event.correlationId, stale = false)
    }

    val digest = when (val result = event.result) {
        is MeshResult.Success -> result.value
        is MeshResult.Failure -> return rejectPending(
            prepared,
            event.correlationId,
            MeshTraceTransition.DIGEST_RESULT,
        )
    }
    if (digest.size != SHA256_BYTES) {
        return rejectPending(prepared, event.correlationId, MeshTraceTransition.DIGEST_RESULT)
    }
    val packetId = PacketIdentity.fromSha256(digest)
    return when (BitchatBaseline2026_08.admissionPolicy(pending.packet)) {
        PacketAdmissionPolicy.VERIFY_SIGNATURE -> {
            val signature = pending.packet.signature
                ?: return rejectPending(prepared, event.correlationId)
            val transcript = pending.signingTranscript
                ?: return rejectPending(prepared, event.correlationId)
            val admissions = prepared.pendingAdmissions.toMutableMap().apply {
                put(
                    event.correlationId,
                    pending.copy(stage = AdmissionStage.AwaitingSignature(packetId)),
                )
            }
            Transition(
                state = prepared.copy(pendingAdmissions = SnapshotMap(admissions)),
                effects = listOf(
                    MeshEffect.VerifySignature(
                        correlationId = event.correlationId,
                        generation = event.generation,
                        sender = pending.packet.sender,
                        transcript = transcript,
                        signature = signature,
                    ),
                ),
                trace = listOf(
                    MeshTrace.record(
                        MeshTraceTransition.DIGEST_RESULT,
                        TraceDecision.APPLIED,
                        event.correlationId,
                    ),
                ),
            )
        }
        PacketAdmissionPolicy.ALLOW_UNSIGNED_MESSAGE,
        PacketAdmissionPolicy.ALLOW_UNSIGNED_FRAGMENT,
        -> admit(prepared, event.correlationId, packetId, event.observedAt, limits)
        PacketAdmissionPolicy.REJECT -> rejectPending(prepared, event.correlationId)
    }
}

internal fun reduceSignature(
    state: MeshState,
    event: MeshEvent.SignatureVerified,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredAdmission(state, event.correlationId, stale = event.generation != state.generation)
    }
    val prepared = state.prepareForCapacity(event.observedAt)
    val pending = prepared.pendingAdmissions[event.correlationId]
        ?: return ignoredAdmission(state, event.correlationId, stale = false)
    val awaiting = pending.stage as? AdmissionStage.AwaitingSignature
        ?: return ignoredAdmission(state, event.correlationId, stale = false)
    val authentic = when (val result = event.result) {
        is MeshResult.Success -> result.value
        is MeshResult.Failure -> false
    }
    if (!authentic) {
        return rejectPending(
            prepared,
            event.correlationId,
            MeshTraceTransition.SIGNATURE_REJECTED,
        )
    }
    return admit(prepared, event.correlationId, awaiting.packetId, event.observedAt, limits)
}

internal fun reduceTimer(
    state: MeshState,
    event: MeshEvent.TimerElapsed,
): Transition<MeshState, MeshEffect> {
    if (event.generation != state.generation || state.lifecycle != MeshLifecycle.RUNNING) {
        return ignoredAdmission(state, event.correlationId, stale = event.generation != state.generation)
    }
    reduceFragmentTimerOrNull(state, event)?.let { return it }
    reduceRelayTimerOrNull(state, event)?.let { return it }
    val write = state.pendingLinkWrites[event.correlationId]
    if (write != null) {
        if (write.timeoutTimerId != event.timerId || event.observedAt < write.expiresAt) {
            return ignoredAdmission(state, event.correlationId, stale = false)
        }
        return Transition(
            state = state.copy(
                observedAt = event.observedAt,
                pendingLinkWrites = SnapshotMap(state.pendingLinkWrites - event.correlationId),
            ),
            trace = listOf(MeshTrace.record(MeshTraceTransition.TIMER, TraceDecision.APPLIED, event.correlationId)),
        )
    }
    val pending = state.pendingAdmissions[event.correlationId]
    if (pending != null) {
        if (pending.timeoutTimerId != event.timerId || event.observedAt < pending.expiresAt) {
            return ignoredAdmission(state, event.correlationId, stale = false)
        }
        val prepared = state.prepareForCapacity(event.observedAt)
        return Transition(
            state = prepared,
            trace = listOf(
                MeshTrace.record(
                    MeshTraceTransition.TIMER,
                    TraceDecision.APPLIED,
                    event.correlationId,
                    itemCount = prepared.pendingAdmissions.size,
                ),
            ),
        )
    }

    val topologyTimer = state.topologyExpiryTimer
    val dedupTimer = state.dedupExpiryTimer
    if (dedupTimer != null &&
        dedupTimer.correlationId == event.correlationId &&
        dedupTimer.timerId == event.timerId
    ) {
        if (event.observedAt < dedupTimer.expiresAt) {
            return ignoredAdmission(state, event.correlationId, stale = false)
        }
        val prepared = state.prepareForCapacity(event.observedAt).copy(dedupExpiryTimer = null)
        val rescheduled = scheduleDedupExpiry(prepared, event.observedAt, cancelExisting = false)
        return Transition(
            state = rescheduled.state,
            effects = rescheduled.effects,
            trace = listOf(
                MeshTrace.record(
                    MeshTraceTransition.TIMER,
                    TraceDecision.APPLIED,
                    event.correlationId,
                    itemCount = prepared.admittedPackets.size,
                ),
            ),
        )
    }
    if (topologyTimer == null ||
        topologyTimer.correlationId != event.correlationId ||
        topologyTimer.timerId != event.timerId ||
        event.observedAt < topologyTimer.expiresAt
    ) {
        return ignoredAdmission(state, event.correlationId, stale = false)
    }
    val prepared = state.prepareForCapacity(event.observedAt).copy(topologyExpiryTimer = null)
    val rescheduled = scheduleTopologyExpiry(prepared, event.observedAt, cancelExisting = false)
    return Transition(
        state = rescheduled.state,
        effects = rescheduled.effects,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.TIMER,
                TraceDecision.APPLIED,
                event.correlationId,
                itemCount = prepared.provisionalBindings.size + prepared.routeObservations.size,
            ),
        ),
    )
}

private fun admit(
    state: MeshState,
    pendingId: CorrelationId,
    packetId: PacketId,
    observedAt: com.yet.bitmessage.foundation.MonotonicTime,
    limits: MeshLimits,
): Transition<MeshState, MeshEffect> {
    val pending = state.pendingAdmissions[pendingId]
        ?: return ignoredAdmission(state, pendingId, stale = false)
    val withoutPending = removePending(state, pendingId)
    val duplicate = withoutPending.state.admittedPackets.containsKey(packetId)
    if (duplicate) {
        val scheduled = withoutPending.state.scheduledRelays[packetId]
        val remainingRelays = withoutPending.state.scheduledRelays.toMutableMap().apply {
            remove(packetId)
        }
        val duplicateState = withoutPending.state.copy(
            scheduledRelays = SnapshotMap(remainingRelays),
        )
        val effects = buildList {
            add(withoutPending.cancel)
            scheduled?.let { relay ->
                add(
                    MeshEffect.Cancel(
                        correlationId = relay.correlationId,
                        generation = state.generation,
                        timerId = relay.timerId,
                    ),
                )
            }
        }
        return Transition(
            state = duplicateState,
            effects = effects,
            trace = listOf(
                MeshTrace.record(
                    MeshTraceTransition.DUPLICATE,
                    TraceDecision.IGNORED,
                    pendingId,
                ),
            ),
        )
    }
    if (withoutPending.state.admittedPackets.size >= limits.maxAdmittedPacketIds) {
        return Transition(
            state = withoutPending.state,
            effects = listOf(withoutPending.cancel),
            trace = listOf(
                MeshTrace.record(
                    MeshTraceTransition.LIMIT_REACHED,
                    TraceDecision.REJECTED,
                    pendingId,
                    itemCount = withoutPending.state.admittedPackets.size,
                ),
            ),
        )
    }

    val admittedPackets = withoutPending.state.admittedPackets.toMutableMap().apply {
        put(packetId, AdmittedPacket(observedAt.plus(limits.dedupLifetime)))
    }
    val bindings = addBinding(withoutPending.state, pending, observedAt, limits)
    val routes = addRouteObservation(
        state = withoutPending.state,
        pending = pending,
        packetId = packetId,
        observedAt = observedAt,
        limits = limits,
    )
    val admittedState = withoutPending.state.copy(
        admittedPackets = SnapshotMap(admittedPackets),
        provisionalBindings = bindings,
        routeObservations = routes,
    )
    val topology = scheduleTopologyExpiry(admittedState, observedAt, cancelExisting = true)
    val dedup = scheduleDedupExpiry(topology.state, observedAt, cancelExisting = true)
    val consequences = admissionConsequences(
        state = dedup.state,
        pending = pending,
        packetId = packetId,
        observedAt = observedAt,
        limits = limits,
    )
    return Transition(
        state = consequences.state,
        effects = listOf(withoutPending.cancel) + topology.effects + dedup.effects + consequences.effects,
        trace = listOf(
            MeshTrace.record(
                MeshTraceTransition.ADMISSION,
                TraceDecision.APPLIED,
                pendingId,
                itemCount = consequences.state.admittedPackets.size,
            ),
        ),
    )
}

private data class PendingRemoval(
    val state: MeshState,
    val cancel: MeshEffect.Cancel,
)

private fun removePending(
    state: MeshState,
    pendingId: CorrelationId,
): PendingRemoval {
    val pending = requireNotNull(state.pendingAdmissions[pendingId])
    val admissions = state.pendingAdmissions.toMutableMap().apply { remove(pendingId) }
    return PendingRemoval(
        state = state.copy(
            pendingAdmissions = SnapshotMap(admissions),
            aggregatePendingBytes = state.aggregatePendingBytes - pending.retainedBytes,
        ),
        cancel = MeshEffect.Cancel(
            correlationId = pendingId,
            generation = state.generation,
            timerId = pending.timeoutTimerId,
        ),
    )
}

private fun rejectPending(
    state: MeshState,
    pendingId: CorrelationId,
    transition: MeshTraceTransition = MeshTraceTransition.ADMISSION,
): Transition<MeshState, MeshEffect> {
    val removed = removePending(state, pendingId)
    return Transition(
        state = removed.state,
        effects = listOf(removed.cancel),
        trace = listOf(
            MeshTrace.record(
                transition,
                TraceDecision.REJECTED,
                pendingId,
            ),
        ),
    )
}

private data class TopologySchedule(
    val state: MeshState,
    val effects: List<MeshEffect>,
)

private fun scheduleTopologyExpiry(
    state: MeshState,
    observedAt: com.yet.bitmessage.foundation.MonotonicTime,
    cancelExisting: Boolean,
): TopologySchedule {
    val earliest = (
        state.provisionalBindings.values.map(PeerBinding::expiresAt) +
            state.routeObservations.values.map(RouteObservation::expiresAt)
        ).minOrNull()
    val existing = state.topologyExpiryTimer
    if (earliest == null) {
        val effects = if (cancelExisting && existing != null) {
            listOf(
                MeshEffect.Cancel(
                    correlationId = existing.correlationId,
                    generation = state.generation,
                    timerId = existing.timerId,
                ),
            )
        } else {
            emptyList()
        }
        return TopologySchedule(state.copy(topologyExpiryTimer = null), effects)
    }
    if (existing?.expiresAt == earliest) return TopologySchedule(state, emptyList())

    val issued = state.issueCorrelation(MeshOperation.TIMER)
    val timer = ExpiryTimer(
        correlationId = issued.correlationId,
        timerId = TimerId.of(TOPOLOGY_TIMER_ID),
        expiresAt = earliest,
    )
    val effects = buildList<MeshEffect> {
        if (cancelExisting && existing != null) {
            add(
                MeshEffect.Cancel(
                    correlationId = existing.correlationId,
                    generation = state.generation,
                    timerId = existing.timerId,
                ),
            )
        }
        add(
            MeshEffect.Schedule(
                correlationId = timer.correlationId,
                generation = state.generation,
                timerId = timer.timerId,
                delay = earliest.elapsedSince(observedAt),
            ),
        )
    }
    return TopologySchedule(
        state = issued.state.copy(topologyExpiryTimer = timer),
        effects = effects,
    )
}

internal data class DedupSchedule(
    val state: MeshState,
    val effects: List<MeshEffect>,
)

internal fun scheduleDedupExpiry(
    state: MeshState,
    observedAt: com.yet.bitmessage.foundation.MonotonicTime,
    cancelExisting: Boolean,
): DedupSchedule {
    val earliest = state.admittedPackets.values.minOfOrNull(AdmittedPacket::expiresAt)
    val existing = state.dedupExpiryTimer
    if (earliest == null) {
        val effects = if (cancelExisting && existing != null) {
            listOf<MeshEffect>(
                MeshEffect.Cancel(
                    correlationId = existing.correlationId,
                    generation = state.generation,
                    timerId = existing.timerId,
                ),
            )
        } else {
            emptyList()
        }
        return DedupSchedule(state.copy(dedupExpiryTimer = null), effects)
    }
    if (existing?.expiresAt == earliest) return DedupSchedule(state, emptyList())

    val issued = state.issueCorrelation(MeshOperation.TIMER)
    val timer = ExpiryTimer(
        correlationId = issued.correlationId,
        timerId = TimerId.of(DEDUP_TIMER_ID),
        expiresAt = earliest,
    )
    val effects = buildList<MeshEffect> {
        if (cancelExisting && existing != null) {
            add(
                MeshEffect.Cancel(
                    correlationId = existing.correlationId,
                    generation = state.generation,
                    timerId = existing.timerId,
                ),
            )
        }
        add(
            MeshEffect.Schedule(
                correlationId = timer.correlationId,
                generation = state.generation,
                timerId = timer.timerId,
                delay = earliest.elapsedSince(observedAt),
            ),
        )
    }
    return DedupSchedule(
        state = issued.state.copy(dedupExpiryTimer = timer),
        effects = effects,
    )
}

private data class AdmissionConsequences(
    val state: MeshState,
    val effects: List<MeshEffect>,
)

private fun admissionConsequences(
    state: MeshState,
    pending: PendingAdmission,
    packetId: PacketId,
    observedAt: com.yet.bitmessage.foundation.MonotonicTime,
    limits: MeshLimits,
): AdmissionConsequences {
    var updated = state
    val effects = mutableListOf<MeshEffect>()
    when (pending.packet.type.knownType) {
        KnownPacketType.MESSAGE -> {
            if (pending.packet.recipient == null || pending.packet.recipient == state.localPeer) {
                val publication = updated.issueCorrelation(MeshOperation.PUBLICATION)
                updated = publication.state
                effects += MeshEffect.PublishPublicPayload(
                    correlationId = publication.correlationId,
                    generation = state.generation,
                    packetId = packetId,
                    sender = pending.packet.sender,
                    ingressLink = pending.source.ingressLink,
                    timestamp = pending.packet.timestamp,
                    payload = pending.packet.payload,
                )
            }
            RelayPolicy.outgoingTtl(pending.packet.ttl)?.let { outgoingTtl ->
                val alreadyPending = updated.pendingRelayEntropy.values.any { it.packetId == packetId } ||
                    updated.pendingRelayEncodes.values.any { it.packetId == packetId }
                val retainedBytes = pending.packet.rawPacket.wireBytes.size
                if (!alreadyPending &&
                    retainedBytes <= limits.maxAggregateRelayRetainedBytes - updated.aggregateRelayRetainedBytes &&
                    retainedBytes <= limits.maxAggregateRetainedPacketBytes - updated.aggregateRetainedPacketBytes
                ) {
                    val entropy = updated.issueCorrelation(MeshOperation.REQUEST_ENTROPY)
                    val requests = entropy.state.pendingRelayEntropy.toMutableMap().apply {
                        put(
                            entropy.correlationId,
                            PendingRelayEntropy(
                                packetId = packetId,
                                source = pending.source,
                                packet = pending.packet,
                                outgoingTtl = outgoingTtl,
                                expiresAt = observedAt.plus(limits.dedupLifetime),
                            ),
                        )
                    }
                    updated = entropy.state.copy(pendingRelayEntropy = SnapshotMap(requests))
                    effects += MeshEffect.RequestEntropy(
                        correlationId = entropy.correlationId,
                        generation = state.generation,
                        packetId = packetId,
                        source = pending.source,
                        outgoingTtl = outgoingTtl,
                    )
                }
            }
        }
        KnownPacketType.FRAGMENT -> {
            val fragment = updated.issueCorrelation(MeshOperation.DECODE_FRAGMENT)
            val requests = fragment.state.pendingFragmentDecodes.toMutableMap().apply {
                put(
                    fragment.correlationId,
                    PendingFragmentDecode(
                        packetId = packetId,
                        source = pending.source,
                        sender = pending.packet.sender,
                        expiresAt = observedAt.plus(limits.dedupLifetime),
                    ),
                )
            }
            updated = fragment.state.copy(pendingFragmentDecodes = SnapshotMap(requests))
            effects += MeshEffect.DecodeFragmentPayload(
                correlationId = fragment.correlationId,
                generation = state.generation,
                packetId = packetId,
                source = pending.source,
                sender = pending.packet.sender,
                payload = pending.packet.payload,
            )
        }
        null -> Unit
    }
    return AdmissionConsequences(updated, effects)
}

private fun addBinding(
    state: MeshState,
    pending: PendingAdmission,
    observedAt: com.yet.bitmessage.foundation.MonotonicTime,
    limits: MeshLimits,
): SnapshotMap<PeerLinkKey, PeerBinding> {
    val key = PeerLinkKey(pending.packet.sender, pending.source.ingressLink)
    val canAdd = state.provisionalBindings.containsKey(key) ||
        (state.provisionalBindings.size < limits.maxPeerObservations &&
            state.provisionalBindings.values.count { it.linkId == pending.source.ingressLink } <
            limits.maxPeerObservationsPerLink)
    if (!canAdd) return state.provisionalBindings

    val bindings = state.provisionalBindings.toMutableMap().apply {
        put(
            key,
            PeerBinding(
                peer = pending.packet.sender,
                linkId = pending.source.ingressLink,
                expiresAt = observedAt.plus(limits.routeLifetime),
            ),
        )
    }
    return SnapshotMap(bindings)
}

private fun addRouteObservation(
    state: MeshState,
    pending: PendingAdmission,
    packetId: PacketId,
    observedAt: com.yet.bitmessage.foundation.MonotonicTime,
    limits: MeshLimits,
): SnapshotMap<PacketId, RouteObservation> {
    val route = pending.packet.route ?: return state.routeObservations
    val canAdd = state.routeObservations.containsKey(packetId) ||
        (state.routeObservations.size < limits.maxRouteObservations &&
            state.routeObservations.values.count { it.sourcePeer == pending.packet.sender } <
            limits.maxRouteObservationsPerSource)
    if (!canAdd) return state.routeObservations

    val routes = state.routeObservations.toMutableMap().apply {
        put(
            packetId,
            RouteObservation(
                sourcePeer = pending.packet.sender,
                ingressLink = pending.source.ingressLink,
                route = route,
                expiresAt = observedAt.plus(limits.routeLifetime),
            ),
        )
    }
    return SnapshotMap(routes)
}

private fun ignoredAdmission(
    state: MeshState,
    correlationId: CorrelationId,
    stale: Boolean,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                if (stale) MeshTraceTransition.STALE_RESULT else MeshTraceTransition.ADMISSION,
                TraceDecision.IGNORED,
                correlationId,
            ),
        ),
    )

private const val SHA256_BYTES: Int = 32
private const val TOPOLOGY_TIMER_ID: String = "mesh-topology-expiry"
private const val DEDUP_TIMER_ID: String = "mesh-dedup-expiry"

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
    val removedWrites = state.pendingLinkWrites.filterValues { it.linkId == linkEvent.linkId }
    val pendingLinkWrites = state.pendingLinkWrites.filterValues { it.linkId != linkEvent.linkId }
    val effects = removedPending.map { (correlationId, pending) ->
        MeshEffect.Cancel(
            correlationId = correlationId,
            generation = event.generation,
            timerId = pending.timeoutTimerId,
        )
    } + removedWrites.map { (correlationId, pending) ->
        MeshEffect.Cancel(correlationId, event.generation, pending.timeoutTimerId)
    }
    return Transition(
        state = state.copy(
            links = SnapshotMap(links),
            provisionalBindings = SnapshotMap(bindings),
            pendingAdmissions = SnapshotMap(retainedPending),
            routeObservations = SnapshotMap(routes),
            pendingLinkWrites = SnapshotMap(pendingLinkWrites),
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

internal fun MeshState.wasIssued(
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

private fun ignoredPacketIngress(
    state: MeshState,
    stale: Boolean,
): Transition<MeshState, MeshEffect> =
    Transition(
        state = state,
        trace = listOf(
            MeshTrace.record(
                if (stale) MeshTraceTransition.STALE_RESULT else MeshTraceTransition.DECODE_RESULT,
                TraceDecision.IGNORED,
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
