package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.TraceSize
import com.yet.bitmessage.foundation.TraceSizeKind
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.PacketId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MeshStateContractTest {
    @Test
    fun productionLimitsMatchTheApprovedSecurityDefaults() {
        val limits = MeshLimits()

        assertEquals(32, limits.maxActiveLinks)
        assertEquals(256, limits.maxPeerObservations)
        assertEquals(8, limits.maxPeerObservationsPerLink)
        assertEquals(256, limits.maxPendingAdmissions)
        assertEquals(8, limits.maxPendingAdmissionsPerLink)
        assertEquals(128 * 1024, limits.maxPendingPacketBytes)
        assertEquals(4 * 1024 * 1024, limits.maxAggregatePendingBytes)
        assertEquals(15.seconds, limits.pendingAdmissionLifetime)
        assertEquals(10_000, limits.maxAdmittedPacketIds)
        assertEquals(5.minutes, limits.dedupLifetime)
        assertEquals(64, limits.maxFragmentStreams)
        assertEquals(4, limits.maxFragmentStreamsPerSource)
        assertEquals(256, limits.maxFragmentsPerStream)
        assertEquals(128 * 1024, limits.maxFragmentStreamBytes)
        assertEquals(4 * 1024 * 1024, limits.maxAggregateFragmentBytes)
        assertEquals(30.seconds, limits.fragmentLifetime)
        assertEquals(512, limits.maxRouteObservations)
        assertEquals(16, limits.maxRouteObservationsPerSource)
        assertEquals(3.minutes, limits.routeLifetime)
        assertEquals(512, limits.maxScheduledRelays)
        assertEquals(8, limits.maxScheduledRelaysPerSource)
        assertEquals(8, limits.maxRelayFanout)
        assertEquals(256, limits.eventMailboxCapacity)
        assertEquals(256, limits.effectQueueCapacity)
        assertEquals(256, limits.traceBufferCapacity)
    }

    @Test
    fun invalidAndOverflowProneLimitRelationshipsAreRejected() {
        assertFailsWith<IllegalArgumentException> { MeshLimits(maxActiveLinks = 0) }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxPeerObservations = 2, maxPeerObservationsPerLink = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxPendingAdmissions = 2, maxPendingAdmissionsPerLink = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxPendingPacketBytes = 1024, maxAggregatePendingBytes = 512)
        }
        assertFailsWith<IllegalArgumentException> { MeshLimits(pendingAdmissionLifetime = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { MeshLimits(dedupLifetime = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxFragmentStreams = 2, maxFragmentStreamsPerSource = 3)
        }
        assertFailsWith<IllegalArgumentException> { MeshLimits(maxFragmentsPerStream = 65_536) }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxFragmentStreamBytes = 1024, maxAggregateFragmentBytes = 512)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxRouteObservations = 2, maxRouteObservationsPerSource = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxScheduledRelays = 2, maxScheduledRelaysPerSource = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxActiveLinks = 4, maxRelayFanout = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxRelayFanout = Int.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshLimits(maxRelayFanout = 8, effectQueueCapacity = 15)
        }
    }

    @Test
    fun snapshotCollectionsDefensivelyOwnMapAndListInputs() {
        val mutableMap = mutableMapOf("a" to 1)
        val mutableList = mutableListOf("link-a")
        val map = SnapshotMap(mutableMap)
        val list = SnapshotList(mutableList)
        mutableMap["b"] = 2
        mutableList += "link-b"

        assertEquals(mapOf("a" to 1), map)
        assertEquals(listOf("link-a"), list)
        assertEquals(mapOf("a" to 1).hashCode(), map.hashCode())
        assertEquals(listOf("link-a").hashCode(), list.hashCode())
    }

    @Test
    fun pendingAndAdmittedStoresStayDistinctAndCountersMatchRetainedBytes() {
        val pendingId = CorrelationId.of("pending-1")
        val admittedId = PacketId.of(MeshFixtures.bytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        val pending = PendingAdmission(
            source = PacketSource.Link(MeshFixtures.linkA),
            packet = MeshFixtures.broadcastPacket,
            signingTranscript = null,
            stage = AdmissionStage.AwaitingDigest,
            retainedBytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes.size,
            expiresAt = MeshFixtures.now.plus(15.seconds),
            timeoutTimerId = TimerId.of("pending-1"),
        )
        val state = MeshFixtures.state().copy(
            pendingAdmissions = SnapshotMap(mapOf(pendingId to pending)),
            admittedPackets = SnapshotMap(
                mapOf(admittedId to AdmittedPacket(MeshFixtures.now.plus(5.minutes))),
            ),
            aggregatePendingBytes = pending.retainedBytes,
        )

        assertEquals(setOf(pendingId), state.pendingAdmissions.keys)
        assertEquals(setOf(admittedId), state.admittedPackets.keys)
        assertFalse(state.pendingAdmissions.containsKey(CorrelationId.of("other")))
        assertEquals(pending.retainedBytes, state.aggregatePendingBytes)
        assertFailsWith<IllegalArgumentException> {
            state.copy(aggregatePendingBytes = pending.retainedBytes - 1)
        }
        assertFailsWith<IllegalArgumentException> { state.copy(aggregateFragmentBytes = -1) }
    }

    @Test
    fun correlationIssuanceIsDeterministicTypedAndChecked() {
        val initial = MeshFixtures.state()
        val first = initial.issueCorrelation(MeshOperation.COMPUTE_DIGEST)
        val replayed = initial.issueCorrelation(MeshOperation.COMPUTE_DIGEST)
        val second = first.state.issueCorrelation(MeshOperation.VERIFY_SIGNATURE)

        assertEquals(first, replayed)
        assertEquals("mesh:3:digest:0", first.correlationId.value)
        assertEquals("mesh:3:verify:1", second.correlationId.value)
        assertEquals(2, second.state.nextCorrelationSequence)
        assertNotEquals(first.correlationId, second.correlationId)
        assertFailsWith<IllegalStateException> {
            initial.copy(nextCorrelationSequence = Long.MAX_VALUE)
                .issueCorrelation(MeshOperation.COMPUTE_DIGEST)
        }
    }

    @Test
    fun capacityPreparationRemovesExpiredEntriesAndRepairsCountersAtomically() {
        val pendingId = CorrelationId.of("pending-expired")
        val admittedId = PacketId.of(MeshFixtures.bytes("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        val retainedBytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes.size
        val state = MeshFixtures.state().copy(
            pendingAdmissions = SnapshotMap(
                mapOf(
                    pendingId to PendingAdmission(
                        source = PacketSource.Link(MeshFixtures.linkA),
                        packet = MeshFixtures.broadcastPacket,
                        signingTranscript = null,
                        stage = AdmissionStage.AwaitingDigest,
                        retainedBytes = retainedBytes,
                        expiresAt = MeshFixtures.now,
                        timeoutTimerId = TimerId.of("pending-expired"),
                    ),
                ),
            ),
            admittedPackets = SnapshotMap(
                mapOf(admittedId to AdmittedPacket(expiresAt = MeshFixtures.now)),
            ),
            aggregatePendingBytes = retainedBytes,
        )

        val prepared = state.prepareForCapacity(MeshFixtures.now)

        assertTrue(prepared.pendingAdmissions.isEmpty())
        assertTrue(prepared.admittedPackets.isEmpty())
        assertEquals(0, prepared.aggregatePendingBytes)
        assertEquals(0, prepared.aggregateFragmentBytes)
    }

    @Test
    fun meshTraceAcceptsOnlyTypedDecisionsAndSafeNumericSizeFacts() {
        val trace = MeshTrace.record(
            transition = MeshTraceTransition.PAYLOAD_RECEIVED,
            decision = TraceDecision.APPLIED,
            correlationId = CorrelationId.of("decode-1"),
            inputBytes = 26,
            itemCount = 1,
        )

        assertEquals(
            listOf(
                TraceSize(TraceSizeKind.INPUT_BYTES, 26),
                TraceSize(TraceSizeKind.ITEM_COUNT, 1),
            ),
            trace.sizeFacts,
        )
        assertFalse(trace.toString().contains("0011223344556677"))
        assertFalse(trace.toString().contains("4142"))
    }

    @Test
    fun lifecycleAndFailureDomainsAreExplicitAndClosed() {
        assertEquals(
            listOf(MeshLifecycle.STOPPED, MeshLifecycle.RUNNING, MeshLifecycle.STOPPING),
            MeshLifecycle.entries,
        )
        assertTrue(MeshFailureCode.entries.containsAll(
            listOf(
                MeshFailureCode.DECODE_FAILED,
                MeshFailureCode.DIGEST_FAILED,
                MeshFailureCode.VERIFICATION_FAILED,
                MeshFailureCode.ENCODING_FAILED,
                MeshFailureCode.TIMER_FAILED,
                MeshFailureCode.WRITE_FAILED,
                MeshFailureCode.EFFECT_EXECUTION_FAILED,
            ),
        ))
    }
}
