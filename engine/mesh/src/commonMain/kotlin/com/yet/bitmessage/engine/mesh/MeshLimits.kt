package com.yet.bitmessage.engine.mesh

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class MeshLimits(
    val maxActiveLinks: Int = 32,
    val maxPeerObservations: Int = 256,
    val maxPeerObservationsPerLink: Int = 8,
    val maxPendingAdmissions: Int = 256,
    val maxPendingAdmissionsPerLink: Int = 8,
    val maxPendingPacketBytes: Int = 128 * 1024,
    val maxAggregatePendingBytes: Int = 4 * 1024 * 1024,
    val pendingAdmissionLifetime: Duration = 15.seconds,
    val maxAdmittedPacketIds: Int = 10_000,
    val dedupLifetime: Duration = 5.minutes,
    val maxFragmentStreams: Int = 64,
    val maxFragmentStreamsPerSource: Int = 4,
    val maxFragmentsPerStream: Int = 256,
    val maxFragmentStreamBytes: Int = 128 * 1024,
    val maxAggregateFragmentBytes: Int = 4 * 1024 * 1024,
    val fragmentLifetime: Duration = 30.seconds,
    val maxRouteObservations: Int = 512,
    val maxRouteObservationsPerSource: Int = 16,
    val routeLifetime: Duration = 3.minutes,
    val maxScheduledRelays: Int = 512,
    val maxScheduledRelaysPerSource: Int = 8,
    val maxRelayFanout: Int = 8,
    val eventMailboxCapacity: Int = 256,
    val effectQueueCapacity: Int = 256,
    val traceBufferCapacity: Int = 256,
) {
    init {
        requirePositive(
            maxActiveLinks,
            maxPeerObservations,
            maxPeerObservationsPerLink,
            maxPendingAdmissions,
            maxPendingAdmissionsPerLink,
            maxPendingPacketBytes,
            maxAggregatePendingBytes,
            maxAdmittedPacketIds,
            maxFragmentStreams,
            maxFragmentStreamsPerSource,
            maxFragmentsPerStream,
            maxFragmentStreamBytes,
            maxAggregateFragmentBytes,
            maxRouteObservations,
            maxRouteObservationsPerSource,
            maxScheduledRelays,
            maxScheduledRelaysPerSource,
            maxRelayFanout,
            eventMailboxCapacity,
            effectQueueCapacity,
            traceBufferCapacity,
        )
        requirePositiveFinite(
            listOf(
                pendingAdmissionLifetime,
                dedupLifetime,
                fragmentLifetime,
                routeLifetime,
            ),
        )
        require(
            eventMailboxCapacity < Int.MAX_VALUE &&
                effectQueueCapacity < Int.MAX_VALUE &&
                traceBufferCapacity < Int.MAX_VALUE
        ) {
            "Mesh channel capacities must be finite and cannot use the unlimited channel sentinel."
        }
        require(maxPeerObservationsPerLink <= maxPeerObservations) {
            "Per-link peer observations cannot exceed the global limit."
        }
        require(maxPendingAdmissionsPerLink <= maxPendingAdmissions) {
            "Per-link pending admissions cannot exceed the global limit."
        }
        require(maxAggregatePendingBytes >= maxPendingPacketBytes) {
            "Aggregate pending bytes cannot be smaller than one pending packet."
        }
        require(maxFragmentStreamsPerSource <= maxFragmentStreams) {
            "Per-source fragment streams cannot exceed the global limit."
        }
        require(maxFragmentsPerStream <= UShort.MAX_VALUE.toInt()) {
            "Fragments per stream must fit the protocol's unsigned 16-bit count."
        }
        require(maxAggregateFragmentBytes >= maxFragmentStreamBytes) {
            "Aggregate fragment bytes cannot be smaller than one fragment stream."
        }
        require(maxRouteObservationsPerSource <= maxRouteObservations) {
            "Per-source route observations cannot exceed the global limit."
        }
        require(maxScheduledRelaysPerSource <= maxScheduledRelays) {
            "Per-source scheduled relays cannot exceed the global limit."
        }
        require(maxRelayFanout <= maxActiveLinks) {
            "Relay fanout cannot exceed the active-link limit."
        }
        require(maxRelayFanout <= Int.MAX_VALUE - FIXED_EFFECT_HEADROOM) {
            "Relay fanout is too large to validate the effect queue safely."
        }
        require(effectQueueCapacity >= maxRelayFanout + FIXED_EFFECT_HEADROOM) {
            "Effect queue must hold one maximum-fanout transition."
        }
    }

    private fun requirePositive(vararg values: Int) {
        require(values.all { it > 0 }) { "Mesh capacities and byte limits must be positive." }
    }

    private fun requirePositiveFinite(values: List<Duration>) {
        require(values.all { it.isFinite() && it > Duration.ZERO }) {
            "Mesh lifetimes must be finite and positive."
        }
    }

    private companion object {
        const val FIXED_EFFECT_HEADROOM: Int = 8
    }
}
