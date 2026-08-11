package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.FragmentId
import com.yet.bitmessage.protocol.bitchat.FragmentPayload
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.PacketType
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FragmentReducerTest {
    @Test
    fun outOfOrderCompletionRemovesStreamAndExplicitlyReinjectsBytes() {
        val engine = MeshEngine()
        val firstRequest = requestFragmentDecode(
            engine,
            readyState(engine),
            fragmentOnePacket,
            digestSeed = 1,
        )
        val afterTail = reduceFragment(engine, firstRequest, fragmentOne)
        assertEquals(setOf(1u.toUShort()), afterTail.state.fragmentStreams.values.single().fragments.keys)

        val secondRequest = requestFragmentDecode(
            engine,
            afterTail.state,
            fragmentZeroPacket,
            digestSeed = 2,
        )
        val completed = reduceFragment(engine, secondRequest, fragmentZero)

        assertTrue(completed.state.fragmentStreams.isEmpty())
        assertEquals(0, completed.state.aggregateFragmentBytes)
        assertTrue(completed.state.pendingAdmissions.isEmpty())
        val reinject = completed.effects.filterIsInstance<MeshEffect.ReinjectPacket>().single()
        assertEquals(originalPacketBytes, reinject.bytes)
        assertEquals(
            PacketSource.Reassembled(MeshFixtures.linkA, fragmentZero.id),
            reinject.source,
        )
        assertTrue(completed.effects.any { it is MeshEffect.Cancel })

        val decodedInner = engine.reduce(
            completed.state,
            MeshFixtures.packetDecoded(
                packet = assertIs<DecodeResult.Success<DecodedPacket>>(
                    BitchatCodec.decode(reinject.bytes),
                ).value,
                source = reinject.source,
                eventGeneration = reinject.generation,
            ),
        )
        val digest = decodedInner.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        assertTrue(decodedInner.state.pendingAdmissions.containsKey(digest.correlationId))

        val replayedTail = engine.reduce(
            completed.state,
            fragmentEvent(firstRequest.effect, fragmentOne),
        )
        assertTrue(replayedTail.state.fragmentStreams.isEmpty())
        assertTrue(replayedTail.effects.isEmpty())
    }

    @Test
    fun identicalDuplicateIsIgnoredWithoutExtendingExpiry() {
        val engine = MeshEngine()
        val first = reduceFragment(
            engine,
            requestFragmentDecode(engine, readyState(engine), fragmentOnePacket, digestSeed = 1),
            fragmentOne,
        )
        val originalStream = first.state.fragmentStreams.values.single()
        val duplicateRequest = requestFragmentDecode(
            engine,
            first.state,
            fragmentOnePacket,
            digestSeed = 2,
            observedAt = MeshFixtures.now.plus(1.milliseconds),
        )
        val duplicate = reduceFragment(engine, duplicateRequest, fragmentOne)

        assertEquals(originalStream, duplicate.state.fragmentStreams.values.single())
        assertEquals(originalStream.retainedBytes, duplicate.state.aggregateFragmentBytes)
        assertTrue(duplicate.effects.isEmpty())
        assertEquals(TraceDecision.IGNORED, duplicate.trace.single().decision)
    }

    @Test
    fun conflictingBytesAtOneIndexRemoveTheWholeStream() {
        val engine = MeshEngine()
        val first = reduceFragment(
            engine,
            requestFragmentDecode(engine, readyState(engine), fragmentOnePacket, digestSeed = 1),
            fragmentOne,
        )
        val conflictRequest = requestFragmentDecode(
            engine,
            first.state,
            fragmentOnePacket,
            digestSeed = 2,
        )
        val conflict = reduceFragment(
            engine,
            conflictRequest,
            fragmentOne.copy(data = bytes("ffffffffffffffffffffffffff")),
        )

        assertTrue(conflict.state.fragmentStreams.isEmpty())
        assertEquals(0, conflict.state.aggregateFragmentBytes)
        assertTrue(conflict.effects.single() is MeshEffect.Cancel)
        assertEquals(TraceDecision.REJECTED, conflict.trace.single().decision)
    }

    @Test
    fun totalOrOriginalTypeConflictRemovesTheWholeStream() {
        val variants = listOf(
            fragmentOne.copy(total = 3u),
            fragmentOne.copy(originalType = PacketType.of(0x03u)),
        )

        variants.forEachIndexed { index, conflictPayload ->
            val engine = MeshEngine()
            val first = reduceFragment(
                engine,
                requestFragmentDecode(engine, readyState(engine), fragmentOnePacket, digestSeed = 1),
                fragmentOne,
            )
            val conflict = reduceFragment(
                engine,
                requestFragmentDecode(
                    engine,
                    first.state,
                    fragmentOnePacket,
                    digestSeed = index + 2,
                ),
                conflictPayload,
            )

            assertTrue(conflict.state.fragmentStreams.isEmpty())
            assertEquals(0, conflict.state.aggregateFragmentBytes)
            assertTrue(conflict.effects.single() is MeshEffect.Cancel)
        }
    }

    @Test
    fun malformedOrOversizedCountsNeverAllocateAStream() {
        val limits = MeshLimits(maxFragmentsPerStream = 2)
        val engine = MeshEngine(limits)
        val malformedRequest = requestFragmentDecode(
            engine,
            readyState(engine),
            fragmentZeroPacket,
            digestSeed = 1,
        )
        val zeroCount = FragmentPayloadCodec.decode(
            bytes("00010203040506070000000002aabb"),
        )
        val malformed = engine.reduce(
            malformedRequest.state,
            MeshEvent.FragmentPayloadDecoded(
                correlationId = malformedRequest.effect.correlationId,
                generation = malformedRequest.effect.generation,
                observedAt = MeshFixtures.now,
                packetId = malformedRequest.effect.packetId,
                source = malformedRequest.effect.source,
                sender = malformedRequest.effect.sender,
                result = zeroCount,
            ),
        )
        assertTrue(malformed.state.fragmentStreams.isEmpty())

        val oversizedRequest = requestFragmentDecode(
            engine,
            malformed.state,
            fragmentZeroPacket,
            digestSeed = 2,
        )
        val oversized = reduceFragment(
            engine,
            oversizedRequest,
            fragmentZero.copy(total = 3u),
        )
        assertTrue(oversized.state.fragmentStreams.isEmpty())
        assertTrue(oversized.effects.isEmpty())
    }

    @Test
    fun streamCapsAreEnforcedGloballyAndPerSource() {
        val otherSender = WirePeerId.of(bytes("8899aabbccddeeff"))
        val otherFragment = fragmentOne.copy(id = FragmentId.of(bytes("0807060504030201")))

        val globalEngine = MeshEngine(
            MeshLimits(maxFragmentStreams = 1, maxFragmentStreamsPerSource = 1),
        )
        val globalFirst = reduceFragment(
            globalEngine,
            requestFragmentDecode(globalEngine, readyState(globalEngine), fragmentOnePacket, 1),
            fragmentOne,
        )
        val globallyFull = reduceFragment(
            globalEngine,
            requestFragmentDecode(
                globalEngine,
                globalFirst.state,
                fragmentOnePacket.copy(sender = otherSender),
                2,
            ),
            otherFragment,
        )
        assertEquals(1, globallyFull.state.fragmentStreams.size)

        val sourceEngine = MeshEngine(
            MeshLimits(maxFragmentStreams = 2, maxFragmentStreamsPerSource = 1),
        )
        val sourceFirst = reduceFragment(
            sourceEngine,
            requestFragmentDecode(sourceEngine, readyState(sourceEngine), fragmentOnePacket, 1),
            fragmentOne,
        )
        val sourceFull = reduceFragment(
            sourceEngine,
            requestFragmentDecode(sourceEngine, sourceFirst.state, fragmentOnePacket, 2),
            otherFragment,
        )
        assertEquals(1, sourceFull.state.fragmentStreams.size)
    }

    @Test
    fun perStreamAndAggregateByteCapsAreCheckedBeforeGrowth() {
        val streamEngine = MeshEngine(
            MeshLimits(maxFragmentStreamBytes = 2, maxAggregateFragmentBytes = 4),
        )
        val head = fragmentZero.copy(data = bytes("aabb"))
        val tail = fragmentOne.copy(data = bytes("cc"))
        val streamFirst = reduceFragment(
            streamEngine,
            requestFragmentDecode(streamEngine, readyState(streamEngine), fragmentZeroPacket, 1),
            head,
        )
        val streamFull = reduceFragment(
            streamEngine,
            requestFragmentDecode(streamEngine, streamFirst.state, fragmentOnePacket, 2),
            tail,
        )
        assertEquals(2, streamFull.state.fragmentStreams.values.single().retainedBytes)
        assertEquals(2, streamFull.state.aggregateFragmentBytes)
        assertTrue(streamFull.effects.isEmpty())

        val aggregateEngine = MeshEngine(
            MeshLimits(maxFragmentStreamBytes = 3, maxAggregateFragmentBytes = 3),
        )
        val aggregateFirst = reduceFragment(
            aggregateEngine,
            requestFragmentDecode(aggregateEngine, readyState(aggregateEngine), fragmentZeroPacket, 1),
            head,
        )
        val other = head.copy(id = FragmentId.of(bytes("0807060504030201")))
        val aggregateFull = reduceFragment(
            aggregateEngine,
            requestFragmentDecode(aggregateEngine, aggregateFirst.state, fragmentZeroPacket, 2),
            other,
        )
        assertEquals(1, aggregateFull.state.fragmentStreams.size)
        assertEquals(2, aggregateFull.state.aggregateFragmentBytes)
    }

    @Test
    fun expiryRemovesOnlyTheMatchingStreamAndLateTimerCannotRemoveReplacement() {
        val limits = MeshLimits()
        val engine = MeshEngine(limits)
        val firstRequest = requestFragmentDecode(
            engine,
            readyState(engine),
            fragmentOnePacket,
            1,
        )
        val first = reduceFragment(
            engine,
            firstRequest,
            fragmentOne,
        )
        val schedule = first.effects.filterIsInstance<MeshEffect.Schedule>().single()
        val early = engine.reduce(
            first.state,
            MeshEvent.TimerElapsed(
                correlationId = schedule.correlationId,
                generation = schedule.generation,
                observedAt = MeshFixtures.now.plus(limits.fragmentLifetime - 1.milliseconds),
                timerId = schedule.timerId,
            ),
        )
        assertEquals(first.state, early.state)

        val deadline = MeshFixtures.now.plus(limits.fragmentLifetime)
        val timerEvent = MeshEvent.TimerElapsed(
            correlationId = schedule.correlationId,
            generation = schedule.generation,
            observedAt = deadline,
            timerId = schedule.timerId,
        )
        val expired = engine.reduce(first.state, timerEvent)
        assertTrue(expired.state.fragmentStreams.isEmpty())
        assertEquals(0, expired.state.aggregateFragmentBytes)

        val replacement = reduceFragment(
            engine,
            requestFragmentDecode(
                engine,
                expired.state,
                fragmentOnePacket,
                2,
                observedAt = deadline.plus(1.milliseconds),
            ),
            fragmentOne,
            observedAt = deadline.plus(1.milliseconds),
        )
        val replacementStream = replacement.state.fragmentStreams.values.single()
        assertNotEquals(schedule.timerId, replacementStream.timerId)

        val late = engine.reduce(replacement.state, timerEvent)
        assertEquals(replacementStream, late.state.fragmentStreams.values.single())

        val lateDecode = engine.reduce(
            replacement.state,
            fragmentEvent(
                effect = firstRequest.effect,
                fragment = fragmentOne,
                observedAt = deadline.plus(1.milliseconds),
            ),
        )
        assertEquals(replacementStream, lateDecode.state.fragmentStreams.values.single())
    }

    private data class FragmentRequest(
        val state: MeshState,
        val effect: MeshEffect.DecodeFragmentPayload,
    )

    private fun requestFragmentDecode(
        engine: MeshEngine,
        state: MeshState,
        packet: DecodedPacket,
        digestSeed: Int,
        observedAt: MonotonicTime = MeshFixtures.now,
    ): FragmentRequest {
        val decoded = engine.reduce(
            state,
            MeshFixtures.packetDecoded(packet = packet, observedAt = observedAt),
        )
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val admitted = engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = digest.generation,
                observedAt = observedAt,
                result = MeshResult.Success(digest(digestSeed)),
            ),
        )
        return FragmentRequest(
            state = admitted.state,
            effect = admitted.effects.filterIsInstance<MeshEffect.DecodeFragmentPayload>().single(),
        )
    }

    private fun reduceFragment(
        engine: MeshEngine,
        request: FragmentRequest,
        fragment: FragmentPayload,
        observedAt: MonotonicTime = MeshFixtures.now,
    ): Transition<MeshState, MeshEffect> =
        engine.reduce(request.state, fragmentEvent(request.effect, fragment, observedAt))

    private fun fragmentEvent(
        effect: MeshEffect.DecodeFragmentPayload,
        fragment: FragmentPayload,
        observedAt: MonotonicTime = MeshFixtures.now,
    ): MeshEvent.FragmentPayloadDecoded =
        MeshEvent.FragmentPayloadDecoded(
            correlationId = effect.correlationId,
            generation = effect.generation,
            observedAt = observedAt,
            packetId = effect.packetId,
            source = effect.source,
            sender = effect.sender,
            result = DecodeResult.Success(fragment),
        )

    private fun readyState(engine: MeshEngine): MeshState =
        engine.reduce(
            MeshFixtures.state(),
            MeshEvent.LinkObserved(
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.Opened(
                    MeshFixtures.linkA,
                    LinkCapabilities(maxWriteBytes = 4096, writeReady = true),
                ),
            ),
        ).state

    private companion object {
        val fragmentZeroPacket: DecodedPacket = MeshFixtures.fragmentZeroPacket
        val fragmentOnePacket: DecodedPacket = MeshFixtures.fragmentOnePacket
        val fragmentZero: FragmentPayload = MeshFixtures.fragmentZero
        val fragmentOne: FragmentPayload = MeshFixtures.fragmentOne
        val originalPacketBytes: Bytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes

        fun digest(seed: Int): Bytes = Bytes.copyOf(ByteArray(32) { seed.toByte() })

        fun bytes(hex: String): Bytes = MeshFixtures.bytes(hex)

    }
}
