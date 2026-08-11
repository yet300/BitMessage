package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.FragmentId
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketType
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.protocol.bitchat.WireRoute
import com.yet.bitmessage.transport.api.LinkCapabilities

class SnapshotMap<K, V>(values: Map<K, V> = emptyMap()) : Map<K, V> by values.toMap() {
    private val snapshot = values.toMap()

    override fun equals(other: Any?): Boolean = other is Map<*, *> && snapshot == other

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = snapshot.toString()
}

class SnapshotList<E>(values: List<E> = emptyList()) : List<E> by values.toList() {
    private val snapshot = values.toList()

    override fun equals(other: Any?): Boolean = other is List<*> && snapshot == other

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = snapshot.toString()
}

enum class MeshLifecycle {
    STOPPED,
    RUNNING,
    STOPPING,
}

data class ActiveLink(
    val capabilities: LinkCapabilities,
    val observedAt: MonotonicTime,
)

data class PeerLinkKey(
    val peer: WirePeerId,
    val linkId: LinkId,
)

data class PeerBinding(
    val peer: WirePeerId,
    val linkId: LinkId,
    val expiresAt: MonotonicTime,
)

sealed interface PacketSource {
    val ingressLink: LinkId

    data class Link(
        override val ingressLink: LinkId,
    ) : PacketSource

    data class Reassembled(
        override val ingressLink: LinkId,
        val fragmentId: FragmentId,
    ) : PacketSource
}

sealed interface AdmissionStage {
    data object AwaitingDigest : AdmissionStage

    data class AwaitingSignature(
        val packetId: PacketId,
    ) : AdmissionStage
}

data class PendingAdmission(
    val source: PacketSource,
    val packet: DecodedPacket,
    val stage: AdmissionStage,
    val retainedBytes: Int,
    val expiresAt: MonotonicTime,
    val timeoutTimerId: TimerId,
) {
    init {
        require(retainedBytes > 0) { "Pending admission bytes must be positive." }
        require(retainedBytes == packet.rawPacket.wireBytes.size) {
            "Pending admission bytes must match the retained packet representation."
        }
    }
}

data class AdmittedPacket(
    val expiresAt: MonotonicTime,
)

data class FragmentStreamKey(
    val sourcePeer: WirePeerId,
    val fragmentId: FragmentId,
)

data class FragmentStream(
    val ingressLink: LinkId,
    val total: UShort,
    val originalType: PacketType,
    val fragments: SnapshotMap<UShort, Bytes>,
    val retainedBytes: Int,
    val expiresAt: MonotonicTime,
    val timerId: TimerId,
) {
    init {
        require(total > 0.toUShort()) { "Fragment stream total must be positive." }
        require(fragments.keys.all { it < total }) { "Fragment index must be less than stream total." }
        require(retainedBytes >= 0) { "Fragment retained bytes must not be negative." }
        require(retainedBytes == checkedByteTotal(fragments.values.map(Bytes::size))) {
            "Fragment retained bytes must match stored fragment bytes."
        }
    }
}

data class RouteObservation(
    val sourcePeer: WirePeerId,
    val ingressLink: LinkId,
    val route: WireRoute,
    val expiresAt: MonotonicTime,
)

data class ScheduledRelay(
    val packetId: PacketId,
    val sourcePeer: WirePeerId,
    val ingressLink: LinkId,
    val packet: DecodedPacket,
    val targets: SnapshotList<LinkId>,
    val outgoingTtl: UByte,
    val correlationId: CorrelationId,
    val timerId: TimerId,
    val expiresAt: MonotonicTime,
)

data class MeshState(
    val generation: Generation,
    val localPeer: WirePeerId,
    val observedAt: MonotonicTime,
    val lifecycle: MeshLifecycle,
    val links: SnapshotMap<LinkId, ActiveLink> = SnapshotMap(),
    val provisionalBindings: SnapshotMap<PeerLinkKey, PeerBinding> = SnapshotMap(),
    val pendingAdmissions: SnapshotMap<CorrelationId, PendingAdmission> = SnapshotMap(),
    val admittedPackets: SnapshotMap<PacketId, AdmittedPacket> = SnapshotMap(),
    val fragmentStreams: SnapshotMap<FragmentStreamKey, FragmentStream> = SnapshotMap(),
    val routeObservations: SnapshotMap<PacketId, RouteObservation> = SnapshotMap(),
    val scheduledRelays: SnapshotMap<PacketId, ScheduledRelay> = SnapshotMap(),
    val aggregatePendingBytes: Int = 0,
    val aggregateFragmentBytes: Int = 0,
    val nextCorrelationSequence: Long = 0,
) {
    init {
        require(aggregatePendingBytes >= 0) { "Aggregate pending bytes must not be negative." }
        require(aggregateFragmentBytes >= 0) { "Aggregate fragment bytes must not be negative." }
        require(nextCorrelationSequence >= 0) { "Correlation sequence must not be negative." }
        require(aggregatePendingBytes == checkedByteTotal(pendingAdmissions.values.map(PendingAdmission::retainedBytes))) {
            "Aggregate pending bytes must match pending admission state."
        }
        require(aggregateFragmentBytes == checkedByteTotal(fragmentStreams.values.map(FragmentStream::retainedBytes))) {
            "Aggregate fragment bytes must match fragment stream state."
        }
    }
}

enum class MeshOperation(
    internal val token: String,
) {
    DECODE_PACKET("decode"),
    COMPUTE_DIGEST("digest"),
    VERIFY_SIGNATURE("verify"),
    DECODE_FRAGMENT("fragment"),
    ENCODE_RELAY("relay"),
    REQUEST_ENTROPY("entropy"),
    TIMER("timer"),
    LINK_WRITE("write"),
    LINK_CLOSE("close"),
    PUBLICATION("publish"),
}

data class CorrelationIssue(
    val state: MeshState,
    val correlationId: CorrelationId,
)

fun MeshState.issueCorrelation(operation: MeshOperation): CorrelationIssue {
    check(nextCorrelationSequence < Long.MAX_VALUE) { "Correlation sequence is exhausted." }
    val correlationId = CorrelationId.of(
        "mesh:${generation.value}:${operation.token}:$nextCorrelationSequence",
    )
    return CorrelationIssue(
        state = copy(nextCorrelationSequence = nextCorrelationSequence + 1),
        correlationId = correlationId,
    )
}

internal fun MeshState.prepareForCapacity(observedAt: MonotonicTime): MeshState {
    val pending = pendingAdmissions.filterValues { it.expiresAt > observedAt }
    val fragments = fragmentStreams.filterValues { it.expiresAt > observedAt }
    return copy(
        observedAt = observedAt,
        provisionalBindings = SnapshotMap(
            provisionalBindings.filterValues { it.expiresAt > observedAt },
        ),
        pendingAdmissions = SnapshotMap(pending),
        admittedPackets = SnapshotMap(admittedPackets.filterValues { it.expiresAt > observedAt }),
        fragmentStreams = SnapshotMap(fragments),
        routeObservations = SnapshotMap(
            routeObservations.filterValues { it.expiresAt > observedAt },
        ),
        scheduledRelays = SnapshotMap(
            scheduledRelays.filterValues { it.expiresAt > observedAt },
        ),
        aggregatePendingBytes = checkedByteTotal(pending.values.map(PendingAdmission::retainedBytes)),
        aggregateFragmentBytes = checkedByteTotal(fragments.values.map(FragmentStream::retainedBytes)),
    )
}

private fun checkedByteTotal(values: Iterable<Int>): Int {
    val total = values.fold(0L) { sum, value ->
        require(value >= 0) { "Retained byte counts must not be negative." }
        sum + value
    }
    require(total <= Int.MAX_VALUE) { "Aggregate retained bytes overflow Int." }
    return total.toInt()
}
