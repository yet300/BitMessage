package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkCloseReason
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkFailureCode
import com.yet.bitmessage.transport.api.LinkResult
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MeshPropertyTest {
    @Test
    fun deterministicHostileSequencesStayWithinEveryApprovedLimit() {
        val limits = MeshLimits()
        val engine = MeshEngine(limits)
        val coverage = mutableSetOf<SequenceCoverage>()
        var broadcastPlacements = 0
        repeat(SEED_COUNT) { seed ->
            val initial = MeshFixtures.state()
            val events = MeshSequenceGenerator(seed.toLong(), engine).events(
                initialState = initial,
                count = EVENTS_PER_SEED,
            )
            events.forEach { event ->
                coverage += event.coverage()
                if (event is MeshEvent.LinkObserved &&
                    event.event is LinkEvent.PayloadReceived &&
                    event.event.bytes == MeshFixtures.broadcastPacket.rawPacket.wireBytes
                ) {
                    broadcastPlacements += 1
                }
            }
            val first = replay(engine, initial, events, limits)
            val second = replay(engine, initial, events, limits)

            assertEquals(first, second, "seed=$seed")
        }
        assertEquals(SequenceCoverage.entries.toSet(), coverage)
        assertTrue(broadcastPlacements > 1, "generator did not place an authenticated duplicate")
    }

    @Test
    fun impossibleCountersAndMismatchedSnapshotsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            MeshFixtures.state().copy(aggregatePendingBytes = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshFixtures.state().copy(aggregatePendingBytes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshFixtures.state().copy(aggregateFragmentBytes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            MeshFixtures.state().copy(
                pendingLinkWrites = SnapshotMap(
                    mapOf(
                        CorrelationId.of("mesh:3:write:0") to MeshFixtures.linkA,
                        CorrelationId.of("mesh:3:write:1") to MeshFixtures.linkA,
                    ),
                ),
            )
        }
    }

    private fun replay(
        engine: MeshEngine,
        initial: MeshState,
        events: List<MeshEvent>,
        limits: MeshLimits,
    ): List<Transition<MeshState, MeshEffect>> {
        var state = initial
        return events.mapIndexed { index, event ->
            val before = state
            val transition = engine.reduce(before, event)
            assertStateWithinLimits(transition, limits, "event=$index type=${event::class.simpleName}")
            if (event.generation != before.generation) {
                assertEquals(before, transition.state, "stale event changed state at index=$index")
            }
            if (event is MeshEvent.SignatureVerified &&
                event.result == MeshResult.Success(false)
            ) {
                assertEquals(
                    before.admittedPackets,
                    transition.state.admittedPackets,
                    "failed authentication changed admitted IDs at index=$index",
                )
            }
            state = transition.state
            transition
        }
    }

    private fun assertStateWithinLimits(
        transition: Transition<MeshState, MeshEffect>,
        limits: MeshLimits,
        context: String,
    ) {
        val state = transition.state
        assertTrue(state.links.size <= limits.maxActiveLinks, context)
        assertTrue(state.provisionalBindings.size <= limits.maxPeerObservations, context)
        state.links.keys.forEach { linkId ->
            assertTrue(
                state.provisionalBindings.values.count { it.linkId == linkId } <=
                    limits.maxPeerObservationsPerLink,
                context,
            )
        }

        assertTrue(state.pendingAdmissions.size <= limits.maxPendingAdmissions, context)
        assertTrue(state.aggregatePendingBytes <= limits.maxAggregatePendingBytes, context)
        assertEquals(
            state.pendingAdmissions.values.sumOf(PendingAdmission::retainedBytes),
            state.aggregatePendingBytes,
            context,
        )
        state.links.keys.forEach { linkId ->
            assertTrue(
                state.pendingAdmissions.values.count { it.source.ingressLink == linkId } <=
                    limits.maxPendingAdmissionsPerLink,
                context,
            )
        }
        assertTrue(
            state.pendingAdmissions.values.all { it.retainedBytes <= limits.maxPendingPacketBytes },
            context,
        )
        assertTrue(state.admittedPackets.size <= limits.maxAdmittedPacketIds, context)
        assertTrue(state.pendingFragmentDecodes.size <= limits.maxAdmittedPacketIds, context)
        assertTrue(state.pendingRelayEntropy.size <= limits.maxAdmittedPacketIds, context)
        assertTrue(state.pendingRelayEncodes.size <= limits.maxAdmittedPacketIds, context)

        assertTrue(state.fragmentStreams.size <= limits.maxFragmentStreams, context)
        assertTrue(state.aggregateFragmentBytes <= limits.maxAggregateFragmentBytes, context)
        assertEquals(
            state.fragmentStreams.values.sumOf(FragmentStream::retainedBytes),
            state.aggregateFragmentBytes,
            context,
        )
        state.fragmentStreams.values.forEach { stream ->
            assertTrue(stream.fragments.size <= limits.maxFragmentsPerStream, context)
            assertTrue(stream.retainedBytes <= limits.maxFragmentStreamBytes, context)
        }
        state.fragmentStreams.keys.groupingBy { it.sourcePeer }.eachCount().values.forEach { count ->
            assertTrue(count <= limits.maxFragmentStreamsPerSource, context)
        }

        assertTrue(state.routeObservations.size <= limits.maxRouteObservations, context)
        state.routeObservations.values.groupingBy { it.sourcePeer }.eachCount().values.forEach { count ->
            assertTrue(count <= limits.maxRouteObservationsPerSource, context)
        }
        assertTrue(state.scheduledRelays.size <= limits.maxScheduledRelays, context)
        state.scheduledRelays.values.groupingBy { it.sourcePeer }.eachCount().values.forEach { count ->
            assertTrue(count <= limits.maxScheduledRelaysPerSource, context)
        }
        state.scheduledRelays.values.forEach { relay ->
            assertTrue(relay.targets.size <= limits.maxRelayFanout, context)
            assertTrue(relay.packet.ttl >= 2u, context)
            assertEquals(
                minOf(relay.packet.ttl.toInt(), 7) - 1,
                relay.outgoingTtl.toInt(),
                context,
            )
        }

        assertTrue(state.pendingLinkWrites.size <= limits.maxActiveLinks, context)
        assertEquals(
            state.pendingLinkWrites.values.size,
            state.pendingLinkWrites.values.toSet().size,
            context,
        )
        assertTrue(transition.effects.size <= limits.effectQueueCapacity, context)
        transition.effects.filterIsInstance<MeshEffect.RequestEntropy>().forEach { request ->
            assertTrue(request.packet.ttl >= 2u, context)
            assertEquals(
                minOf(request.packet.ttl.toInt(), 7) - 1,
                request.outgoingTtl.toInt(),
                context,
            )
        }
        transition.effects.filterIsInstance<MeshEffect.EncodeRelay>().forEach { relay ->
            assertTrue(relay.targets.size <= limits.maxRelayFanout, context)
            assertTrue(relay.packet.ttl >= 2u, context)
            assertEquals(
                minOf(relay.packet.ttl.toInt(), 7) - 1,
                relay.outgoingTtl.toInt(),
                context,
            )
        }
    }

    private class MeshSequenceGenerator(
        seed: Long,
        private val engine: MeshEngine,
    ) {
        private val random = DeterministicRandom(seed)
        private val pendingEffects = mutableListOf<MeshEffect>()
        private var now: MonotonicTime = MeshFixtures.now
        private var state: MeshState = MeshFixtures.state()

        fun events(initialState: MeshState, count: Int): List<MeshEvent> {
            state = initialState
            val events = ArrayList<MeshEvent>(count)
            repeat(count) { index ->
                val event = when (index) {
                    0 -> opened(MeshFixtures.linkA)
                    1 -> opened(MeshFixtures.linkB)
                    else -> nextEvent()
                }
                events += event
                val transition = engine.reduce(state, event)
                state = transition.state
                retainExecutableEffects(transition.effects)
            }
            return events
        }

        private fun nextEvent(): MeshEvent {
            if (pendingEffects.isNotEmpty() && random.nextInt(10) < 7) {
                val effect = pendingEffects.removeAt(random.nextInt(pendingEffects.size))
                return eventFor(effect)
            }
            return externalEvent()
        }

        private fun externalEvent(): MeshEvent =
            when (random.nextInt(12)) {
                0 -> opened(MeshFixtures.linkA)
                1 -> opened(MeshFixtures.linkB)
                2 -> readiness(randomLink())
                3, 4 -> payload(MeshFixtures.broadcastPacket)
                5 -> payload(signedPacket)
                6 -> payload(if (random.nextInt(2) == 0) fragmentZeroPacket else fragmentOnePacket)
                7 -> closed(randomLink())
                8 -> MeshEvent.LinkObserved(
                    generation = state.generation.next(),
                    observedAt = tick(),
                    event = LinkEvent.PayloadReceived(
                        MeshFixtures.linkA,
                        MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                    ),
                )
                9 -> MeshEvent.PacketDecoded(
                    correlationId = CorrelationId.of("mesh:${state.generation.value}:decode:999999"),
                    generation = state.generation,
                    observedAt = tick(),
                    source = PacketSource.Link(MeshFixtures.linkA),
                    result = DecodeResult.Failure(DecodeError.MALFORMED_FIELD),
                )
                10 -> MeshEvent.EffectFailed(
                    correlationId = CorrelationId.of("mesh:${state.generation.value}:digest:999998"),
                    generation = state.generation,
                    observedAt = tick(),
                    code = MeshFailureCode.EFFECT_EXECUTION_FAILED,
                )
                else -> MeshEvent.RuntimeStarted(
                    generation = state.generation.next(),
                    observedAt = tick(),
                    localPeer = MeshFixtures.localPeer,
                )
            }

        private fun eventFor(effect: MeshEffect): MeshEvent =
            when (effect) {
                is MeshEffect.DecodePacket -> MeshEvent.PacketDecoded(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = tick(),
                    source = effect.source,
                    result = if (random.nextInt(8) == 0) {
                        DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
                    } else {
                        BitchatCodec.decode(effect.bytes)
                    },
                )
                is MeshEffect.ComputePacketDigest -> MeshEvent.PacketDigestComputed(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = tick(),
                    result = if (random.nextInt(12) == 0) {
                        MeshResult.Failure(MeshFailureCode.DIGEST_FAILED)
                    } else {
                        MeshResult.Success(stableDigest(effect.input.canonicalBytes))
                    },
                )
                is MeshEffect.VerifySignature -> MeshEvent.SignatureVerified(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = tick(),
                    result = if (random.nextInt(12) == 0) {
                        MeshResult.Failure(MeshFailureCode.VERIFICATION_FAILED)
                    } else {
                        MeshResult.Success(random.nextInt(2) == 0)
                    },
                )
                is MeshEffect.DecodeFragmentPayload -> MeshEvent.FragmentPayloadDecoded(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = tick(),
                    packetId = effect.packetId,
                    source = effect.source,
                    sender = effect.sender,
                    result = if (random.nextInt(8) == 0) {
                        DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
                    } else {
                        FragmentPayloadCodec.decode(effect.payload)
                    },
                )
                is MeshEffect.EncodeRelay -> MeshEvent.RelayEncoded(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = tick(),
                    packetId = effect.packetId,
                    targets = effect.targets,
                    result = RelayEncoding.withTtl(effect.packet, effect.outgoingTtl),
                )
                is MeshEffect.RequestEntropy -> MeshEvent.EntropyProvided(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = tick(),
                    packetId = effect.packetId,
                    source = effect.source,
                    packet = effect.packet,
                    outgoingTtl = effect.outgoingTtl,
                    result = MeshResult.Success(
                        if (random.nextInt(8) == 0) {
                            Bytes.copyOf(byteArrayOf(random.nextByte()))
                        } else {
                            Bytes.copyOf(byteArrayOf(random.nextByte(), random.nextByte()))
                        },
                    ),
                )
                is MeshEffect.Schedule -> {
                    now = now.plus(effect.delay)
                    MeshEvent.TimerElapsed(
                        correlationId = effect.correlationId,
                        generation = effect.generation,
                        observedAt = now,
                        timerId = effect.timerId,
                    )
                }
                is MeshEffect.WriteLink -> linkResult(effect)
                is MeshEffect.Cancel,
                is MeshEffect.CloseLink,
                is MeshEffect.PublishPublicPayload,
                -> error("Non-executable effect was retained: ${effect::class.simpleName}")
            }

        private fun linkResult(effect: MeshEffect.WriteLink): MeshEvent.LinkCompleted {
            val command = effect.command
            val result = when (random.nextInt(6)) {
                0 -> LinkResult.Written(command.linkId, command.correlationId, command.generation)
                1 -> LinkResult.Backpressured(command.linkId, command.correlationId, command.generation)
                2 -> LinkResult.PayloadTooLarge(
                    command.linkId,
                    command.correlationId,
                    command.generation,
                    maximumBytes = max(1, command.bytes.size - 1),
                )
                3 -> LinkResult.Disconnected(command.linkId, command.correlationId, command.generation)
                4 -> LinkResult.Unsupported(command.linkId, command.correlationId, command.generation)
                else -> LinkResult.Failed(
                    command.linkId,
                    command.correlationId,
                    command.generation,
                    LinkFailureCode.TRANSIENT,
                )
            }
            return MeshEvent.LinkCompleted(
                correlationId = command.correlationId,
                generation = command.generation,
                observedAt = tick(),
                result = result,
            )
        }

        private fun retainExecutableEffects(effects: List<MeshEffect>) {
            effects.forEach { effect ->
                when (effect) {
                    is MeshEffect.Cancel -> pendingEffects.removeAll { pending ->
                        pending is MeshEffect.Schedule &&
                            pending.correlationId == effect.correlationId &&
                            pending.timerId == effect.timerId
                    }
                    is MeshEffect.CloseLink,
                    is MeshEffect.PublishPublicPayload,
                    -> Unit
                    else -> pendingEffects += effect
                }
            }
        }

        private fun opened(linkId: LinkId): MeshEvent.LinkObserved =
            MeshEvent.LinkObserved(
                generation = state.generation,
                observedAt = tick(),
                event = LinkEvent.Opened(
                    linkId,
                    LinkCapabilities(
                        maxWriteBytes = if (random.nextInt(5) == 0) 16 else 4096,
                        writeReady = random.nextInt(5) != 0,
                    ),
                ),
            )

        private fun readiness(linkId: LinkId): MeshEvent.LinkObserved =
            MeshEvent.LinkObserved(
                generation = state.generation,
                observedAt = tick(),
                event = LinkEvent.ReadinessChanged(
                    linkId,
                    LinkCapabilities(
                        maxWriteBytes = if (random.nextInt(4) == 0) 16 else 4096,
                        writeReady = random.nextInt(3) != 0,
                    ),
                ),
            )

        private fun payload(packet: DecodedPacket): MeshEvent.LinkObserved =
            MeshEvent.LinkObserved(
                generation = state.generation,
                observedAt = tick(),
                event = LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    packet.rawPacket.wireBytes,
                ),
            )

        private fun closed(linkId: LinkId): MeshEvent.LinkObserved =
            MeshEvent.LinkObserved(
                generation = state.generation,
                observedAt = tick(),
                event = LinkEvent.Closed(linkId, LinkCloseReason.REMOTE_CLOSED),
            )

        private fun randomLink(): LinkId =
            if (random.nextInt(2) == 0) MeshFixtures.linkA else MeshFixtures.linkB

        private fun tick(): MonotonicTime {
            now = now.plus(1.milliseconds)
            return now
        }
    }

    private class DeterministicRandom(seed: Long) {
        private var state: Long = seed xor -7046029254386353131L

        fun nextInt(bound: Int): Int {
            require(bound > 0)
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 1) % bound.toLong()).toInt()
        }

        fun nextByte(): Byte = nextInt(256).toByte()
    }

    private enum class SequenceCoverage {
        LINK_CHURN,
        PAYLOAD,
        DECODE,
        DIGEST,
        AUTHENTICATION,
        FRAGMENT,
        ENTROPY,
        TIMER,
        WRITE,
        STALE_GENERATION,
        EFFECT_FAILURE,
    }

    private fun MeshEvent.coverage(): SequenceCoverage =
        when (this) {
            is MeshEvent.LinkObserved -> when {
                generation != MeshFixtures.generation -> SequenceCoverage.STALE_GENERATION
                event is LinkEvent.PayloadReceived -> SequenceCoverage.PAYLOAD
                else -> SequenceCoverage.LINK_CHURN
            }
            is MeshEvent.PacketDecoded -> SequenceCoverage.DECODE
            is MeshEvent.PacketDigestComputed -> SequenceCoverage.DIGEST
            is MeshEvent.SignatureVerified -> SequenceCoverage.AUTHENTICATION
            is MeshEvent.FragmentPayloadDecoded -> SequenceCoverage.FRAGMENT
            is MeshEvent.RelayEncoded -> SequenceCoverage.WRITE
            is MeshEvent.EntropyProvided -> SequenceCoverage.ENTROPY
            is MeshEvent.TimerElapsed -> SequenceCoverage.TIMER
            is MeshEvent.LinkCompleted -> SequenceCoverage.WRITE
            is MeshEvent.EffectFailed -> SequenceCoverage.EFFECT_FAILURE
            is MeshEvent.RuntimeStarted,
            is MeshEvent.RuntimeStopping,
            -> SequenceCoverage.STALE_GENERATION
        }

    private companion object {
        const val SEED_COUNT: Int = 100
        const val EVENTS_PER_SEED: Int = 250

        val signedPacket: DecodedPacket = decode(
            Bytes.copyOf(
                bytes("0202070102030405060708020000000200112233445566774142").copyToByteArray() +
                    ByteArray(64) { 0x5a },
            ),
        )
        val fragmentZeroPacket: DecodedPacket = decode(
            bytes(
                "0220030102030405060708000000001a0011223344556677" +
                    "0001020304050607000000020202020301020304050607080000",
            ),
        )
        val fragmentOnePacket: DecodedPacket = decode(
            bytes(
                "0220030102030405060708000000001a0011223344556677" +
                    "0001020304050607000100020200000200112233445566774142",
            ),
        )

        fun stableDigest(input: Bytes): Bytes {
            var hash = 0x811c9dc5u
            input.copyToByteArray().forEach { byte ->
                hash = (hash xor byte.toUByte().toUInt()) * 0x01000193u
            }
            return Bytes.copyOf(
                ByteArray(32) { index ->
                    val shift = (index % Int.SIZE_BYTES) * Byte.SIZE_BITS
                    ((hash shr shift) xor index.toUInt()).toByte()
                },
            )
        }

        fun bytes(hex: String): Bytes = MeshFixtures.bytes(hex)

        fun decode(bytes: Bytes): DecodedPacket =
            assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(bytes)).value
    }
}
