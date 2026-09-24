package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.foundation.TraceDecision
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.protocol.bitchat.SigningTranscript
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.protocol.bitchat.WireRoute
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkFailureCode
import com.yet.bitmessage.transport.api.LinkResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class RelayPolicyTest {
    @Test
    fun localRelayTtlPolicyCapsUntrustedLargeValues() {
        assertEquals(null, RelayPolicy.outgoingTtl(0u))
        assertEquals(null, RelayPolicy.outgoingTtl(1u))
        assertEquals(1u.toUByte(), RelayPolicy.outgoingTtl(2u))
        assertEquals(6u.toUByte(), RelayPolicy.outgoingTtl(7u))
        assertEquals(6u.toUByte(), RelayPolicy.outgoingTtl(255u))
    }

    @Test
    fun broadcastAndLocalRecipientDispatchWhileNonlocalRecipientDoesNot() {
        val engine = MeshEngine()
        val broadcast = admitUnsigned(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val local = admitUnsigned(engine, readyState(engine), localRecipientPacket)
        val nonlocal = admitUnsigned(engine, readyState(engine), nonlocalRecipientPacket)

        assertEquals(1, broadcast.effects.count { it is MeshEffect.PublishPublicPayload })
        assertEquals(1, local.effects.count { it is MeshEffect.PublishPublicPayload })
        assertTrue(nonlocal.effects.none { it is MeshEffect.PublishPublicPayload })
    }

    @Test
    fun fragmentAdmissionDecodesMetadataWithoutPublicDispatchOrOuterRelay() {
        val engine = MeshEngine()
        val admitted = admitUnsigned(engine, readyState(engine), fragmentPacket)

        val fragment = assertIs<MeshEffect.DecodeFragmentPayload>(
            admitted.effects.single { it is MeshEffect.DecodeFragmentPayload },
        )
        assertEquals(fragmentPacket.payload, fragment.payload)
        assertTrue(admitted.effects.none { it is MeshEffect.PublishPublicPayload })
        assertTrue(admitted.effects.none { it is MeshEffect.RequestEntropy })
    }

    @Test
    fun localDispatchIsIndependentOfTheLocalRelayTtlPolicy() {
        val engine = MeshEngine()
        val ttlZero = admitUnsigned(engine, readyState(engine), packetWithTtl(0u))
        val ttlOne = admitUnsigned(engine, readyState(engine), packetWithTtl(1u))
        val ttlTwo = admitUnsigned(engine, readyState(engine), packetWithTtl(2u))
        val ttlSeven = admitUnsigned(engine, readyState(engine), packetWithTtl(7u))
        val hostile = admitUnsigned(engine, readyState(engine), packetWithTtl(255u))

        listOf(ttlZero, ttlOne, ttlTwo, ttlSeven, hostile).forEach { transition ->
            assertEquals(1, transition.effects.count { it is MeshEffect.PublishPublicPayload })
        }
        assertTrue(ttlZero.effects.none { it is MeshEffect.RequestEntropy })
        assertTrue(ttlOne.effects.none { it is MeshEffect.RequestEntropy })
        assertEquals(1u.toUByte(), ttlTwo.entropyRequest().outgoingTtl)
        assertEquals(6u.toUByte(), ttlSeven.entropyRequest().outgoingTtl)
        assertEquals(6u.toUByte(), hostile.entropyRequest().outgoingTtl)
    }

    @Test
    fun authenticatedDuplicateDoesNotDispatchOrRequestRelayTwice() {
        val engine = MeshEngine()
        val first = admitUnsigned(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val duplicate = admitUnsigned(engine, first.state, MeshFixtures.broadcastPacket)

        assertEquals(1, duplicate.state.admittedPackets.size)
        assertTrue(duplicate.effects.none { it is MeshEffect.PublishPublicPayload })
        assertTrue(duplicate.effects.none { it is MeshEffect.RequestEntropy })
    }

    @Test
    fun oneEarliestDedupTimerExpiresOnlyAtItsExplicitDeadline() {
        val limits = MeshLimits()
        val engine = MeshEngine(limits)
        val admitted = admitUnsigned(engine, readyState(engine), MeshFixtures.broadcastPacket)
        val timer = requireNotNull(admitted.state.dedupExpiryTimer)
        val early = engine.reduce(
            admitted.state,
            MeshEvent.TimerElapsed(
                correlationId = timer.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now.plus(1.minutes),
                timerId = timer.timerId,
            ),
        )
        val expired = engine.reduce(
            admitted.state,
            MeshEvent.TimerElapsed(
                correlationId = timer.correlationId,
                generation = MeshFixtures.generation,
                observedAt = timer.expiresAt,
                timerId = timer.timerId,
            ),
        )

        assertEquals(admitted.state, early.state)
        assertTrue(expired.state.admittedPackets.isEmpty())
        assertEquals(null, expired.state.dedupExpiryTimer)
    }

    @Test
    fun fullUnexpiredDedupCacheRejectsNewAdmissionWithoutWeakeningSentinel() {
        val limits = MeshLimits(maxAdmittedPacketIds = 1)
        val engine = MeshEngine(limits)
        val sentinel = PacketId.of(bytes("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        val initial = readyState(engine).copy(
            admittedPackets = SnapshotMap(
                mapOf(sentinel to AdmittedPacket(MeshFixtures.now.plus(5.minutes))),
            ),
        )
        val rejected = admitUnsigned(engine, initial, MeshFixtures.broadcastPacket)

        assertEquals(setOf(sentinel), rejected.state.admittedPackets.keys)
        assertTrue(rejected.effects.none { it is MeshEffect.PublishPublicPayload })
        assertTrue(rejected.effects.none { it is MeshEffect.RequestEntropy })
    }

    @Test
    fun fallbackTargetsExcludeIngressAndIneligibleLinksThenSortAndCap() {
        val limits = MeshLimits(maxRelayFanout = 2)
        val linkC = LinkId.of("link-c")
        val linkD = LinkId.of("link-d")
        val state = MeshFixtures.state().copy(
            links = SnapshotMap(
                mapOf(
                    linkC to ActiveLink(LinkCapabilities(512, true), MeshFixtures.now),
                    MeshFixtures.linkB to ActiveLink(LinkCapabilities(512, true), MeshFixtures.now),
                    MeshFixtures.linkA to ActiveLink(LinkCapabilities(512, true), MeshFixtures.now),
                    linkD to ActiveLink(LinkCapabilities(512, false), MeshFixtures.now),
                    LinkId.of("link-small") to ActiveLink(LinkCapabilities(25, true), MeshFixtures.now),
                ),
            ),
        )

        assertEquals(
            listOf(MeshFixtures.linkB, linkC),
            RelayPolicy.selectTargets(
                state = state,
                packet = MeshFixtures.broadcastPacket,
                ingress = MeshFixtures.linkA,
                encodedSize = 26,
                limits = limits,
            ),
        )
    }

    @Test
    fun sourceRouteUsesOneUnambiguousNextHopAndSuppressesSelfLoop() {
        val nextPeer = WirePeerId.of(bytes("8899aabbccddeeff"))
        val linkC = LinkId.of("link-c")
        val links = SnapshotMap(
            mapOf(
                MeshFixtures.linkA to ActiveLink(LinkCapabilities(512, true), MeshFixtures.now),
                MeshFixtures.linkB to ActiveLink(LinkCapabilities(512, true), MeshFixtures.now),
                linkC to ActiveLink(LinkCapabilities(512, true), MeshFixtures.now),
            ),
        )
        val directKey = PeerLinkKey(nextPeer, MeshFixtures.linkB)
        val directState = MeshFixtures.state().copy(
            links = links,
            provisionalBindings = SnapshotMap(
                mapOf(
                    directKey to PeerBinding(
                        nextPeer,
                        MeshFixtures.linkB,
                        MeshFixtures.now.plus(3.minutes),
                    ),
                ),
            ),
        )
        val routed = MeshFixtures.broadcastPacket.copy(
            route = WireRoute(listOf(MeshFixtures.localPeer, nextPeer)),
        )

        assertEquals(
            listOf(MeshFixtures.linkB),
            RelayPolicy.selectTargets(directState, routed, MeshFixtures.linkA, 26, MeshLimits()),
        )

        val ambiguous = directState.copy(
            provisionalBindings = SnapshotMap(
                directState.provisionalBindings +
                    (PeerLinkKey(nextPeer, linkC) to PeerBinding(
                        nextPeer,
                        linkC,
                        MeshFixtures.now.plus(3.minutes),
                    )),
            ),
        )
        assertEquals(
            listOf(MeshFixtures.linkB, linkC),
            RelayPolicy.selectTargets(ambiguous, routed, MeshFixtures.linkA, 26, MeshLimits()),
        )

        val loop = routed.copy(
            route = WireRoute(listOf(MeshFixtures.localPeer, nextPeer, MeshFixtures.localPeer)),
        )
        assertTrue(
            RelayPolicy.selectTargets(directState, loop, MeshFixtures.linkA, 26, MeshLimits()).isEmpty(),
        )

        val expiredBinding = directState.copy(
            provisionalBindings = SnapshotMap(
                mapOf(
                    directKey to PeerBinding(nextPeer, MeshFixtures.linkB, MeshFixtures.now),
                ),
            ),
        )
        assertEquals(
            listOf(MeshFixtures.linkB, linkC),
            RelayPolicy.selectTargets(expiredBinding, routed, MeshFixtures.linkA, 26, MeshLimits()),
        )
    }

    @Test
    fun routeObservationsRespectGlobalAndPerSourceLimits() {
        val nextPeer = WirePeerId.of(bytes("8899aabbccddeeff"))
        val routed = MeshFixtures.broadcastPacket.copy(
            route = WireRoute(listOf(MeshFixtures.localPeer, nextPeer)),
        )
        val occupiedId = PacketId.of(bytes("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        val occupiedByOtherSource = RouteObservation(
            sourcePeer = nextPeer,
            ingressLink = MeshFixtures.linkB,
            route = WireRoute(listOf(nextPeer)),
            expiresAt = MeshFixtures.now.plus(1.minutes),
        )

        val globalEngine = MeshEngine(
            MeshLimits(maxRouteObservations = 1, maxRouteObservationsPerSource = 1),
        )
        val globallyFull = admitUnsigned(
            globalEngine,
            readyState(globalEngine).copy(
                routeObservations = SnapshotMap(mapOf(occupiedId to occupiedByOtherSource)),
            ),
            routed,
        )
        assertEquals(setOf(occupiedId), globallyFull.state.routeObservations.keys)

        val sourceEngine = MeshEngine(
            MeshLimits(maxRouteObservations = 2, maxRouteObservationsPerSource = 1),
        )
        val sourceFull = admitUnsigned(
            sourceEngine,
            readyState(sourceEngine).copy(
                routeObservations = SnapshotMap(
                    mapOf(
                        occupiedId to occupiedByOtherSource.copy(
                            sourcePeer = MeshFixtures.broadcastPacket.sender,
                        ),
                    ),
                ),
            ),
            routed,
        )
        assertEquals(setOf(occupiedId), sourceFull.state.routeObservations.keys)
    }

    @Test
    fun twoByteEntropyMapsExactlyIntoInclusiveZeroToFiveHundredMilliseconds() {
        assertEquals(0.milliseconds, RelayPolicy.relayDelay(bytes("0000")))
        assertEquals(500.milliseconds, RelayPolicy.relayDelay(bytes("01f4")))
        assertEquals(405.milliseconds, RelayPolicy.relayDelay(bytes("ffff")))
    }

    @Test
    fun entropyTimerEncodingAndWriteCompletionFormOneCorrelatedRelay() {
        val engine = MeshEngine()
        val state = readyStateWithRelayLink(engine)
        val admitted = admitUnsigned(engine, state, MeshFixtures.broadcastPacket)
        val entropy = admitted.entropyRequest()
        val entropyEvent = entropyResult(entropy, bytes("0001"))
        val scheduled = engine.reduce(
            admitted.state,
            entropyEvent,
        )
        val schedule = assertIs<MeshEffect.Schedule>(
            scheduled.effects.single { it is MeshEffect.Schedule },
        )
        assertEquals(1.milliseconds, schedule.delay)
        assertEquals(1, scheduled.state.scheduledRelays.size)

        val timerFired = engine.reduce(
            scheduled.state,
            MeshEvent.TimerElapsed(
                correlationId = schedule.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now.plus(schedule.delay),
                timerId = schedule.timerId,
            ),
        )
        val encode = assertIs<MeshEffect.EncodeRelay>(timerFired.effects.single())
        assertTrue(timerFired.state.scheduledRelays.isEmpty())
        val repeatedEntropy = engine.reduce(timerFired.state, entropyEvent)
        assertTrue(repeatedEntropy.effects.isEmpty())
        assertTrue(repeatedEntropy.state.scheduledRelays.isEmpty())

        val encoded = RelayEncoding.withTtl(encode.packet, encode.outgoingTtl)
        val relayEvent = MeshEvent.RelayEncoded(
            correlationId = encode.correlationId,
            generation = MeshFixtures.generation,
            observedAt = MeshFixtures.now.plus(schedule.delay),
            packetId = encode.packetId,
            targets = encode.targets,
            result = encoded,
        )
        val relayEncoded = engine.reduce(
            timerFired.state,
            relayEvent,
        )
        val write = relayEncoded.effects.filterIsInstance<MeshEffect.WriteLink>().single()
        assertEquals(MeshFixtures.linkB, write.command.linkId)
        assertEquals(2u.toUByte(), write.command.bytes[2])

        val completed = engine.reduce(
            relayEncoded.state,
            MeshEvent.LinkCompleted(
                correlationId = write.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now.plus(schedule.delay),
                result = LinkResult.Written(
                    write.command.linkId,
                    write.command.correlationId,
                    write.command.generation,
                ),
            ),
        )
        assertEquals(1, completed.effects.filterIsInstance<MeshEffect.Cancel>().size)

        val repeatedEncoding = engine.reduce(completed.state, relayEvent)
        assertTrue(repeatedEncoding.effects.isEmpty())
        assertEquals(TraceDecision.IGNORED, repeatedEncoding.trace.single().decision)

        val duplicate = engine.reduce(
            completed.state,
            MeshEvent.LinkCompleted(
                correlationId = write.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now.plus(schedule.delay),
                result = LinkResult.Written(
                    write.command.linkId,
                    write.command.correlationId,
                    write.command.generation,
                ),
            ),
        )
        assertEquals(TraceDecision.IGNORED, duplicate.trace.single().decision)
    }

    @Test
    fun everyLinkResultCompletesWithoutRetryAndOnlyWrittenIsSuccessful() {
        val engine = MeshEngine()
        val relay = relayToWrite(engine, MeshFixtures.broadcastPacket, signed = false)
        val command = relay.write.command
        val results = listOf(
            LinkResult.Written(command.linkId, command.correlationId, command.generation),
            LinkResult.Backpressured(command.linkId, command.correlationId, command.generation),
            LinkResult.PayloadTooLarge(
                command.linkId,
                command.correlationId,
                command.generation,
                maximumBytes = command.bytes.size - 1,
            ),
            LinkResult.Disconnected(command.linkId, command.correlationId, command.generation),
            LinkResult.Unsupported(command.linkId, command.correlationId, command.generation),
            LinkResult.Failed(
                command.linkId,
                command.correlationId,
                command.generation,
                LinkFailureCode.TRANSIENT,
            ),
        )

        results.forEachIndexed { index, result ->
            val completed = engine.reduce(
                relay.state,
                MeshEvent.LinkCompleted(
                    correlationId = command.correlationId,
                    generation = command.generation,
                    observedAt = MeshFixtures.now,
                    result = result,
                ),
            )
            assertEquals(1, completed.effects.filterIsInstance<MeshEffect.Cancel>().size)
            assertEquals(
                if (index == 0) TraceDecision.APPLIED else TraceDecision.REJECTED,
                completed.trace.single().decision,
            )
        }
    }

    @Test
    fun signedAdmissionRelayKeepsSignatureAndSigningTranscript() {
        val engine = MeshEngine()
        val relay = relayToWrite(engine, signedPacket, signed = true)
        val relayed = decode(relay.write.command.bytes)

        assertEquals(6u.toUByte(), relayed.ttl)
        assertEquals(signedPacket.signature, relayed.signature)
        assertEquals(SigningTranscript.build(signedPacket), SigningTranscript.build(relayed))
    }

    @Test
    fun scheduledRelayPressureIsBoundedGloballyAndPerSource() {
        val globalLimits = MeshLimits(maxScheduledRelays = 1, maxScheduledRelaysPerSource = 1)
        val globalEngine = MeshEngine(globalLimits)
        val globalAdmitted = admitUnsigned(
            globalEngine,
            readyStateWithRelayLink(globalEngine),
            MeshFixtures.broadcastPacket,
        )
        val occupiedByOtherSource = existingRelay(
            sourcePeer = WirePeerId.of(bytes("8899aabbccddeeff")),
        )
        val globallyFull = globalEngine.reduce(
            globalAdmitted.state.copy(
                scheduledRelays = SnapshotMap(
                    mapOf(occupiedByOtherSource.packetId to occupiedByOtherSource),
                ),
            ),
            entropyResult(globalAdmitted.entropyRequest(), bytes("0001")),
        )
        assertEquals(1, globallyFull.state.scheduledRelays.size)
        assertTrue(globallyFull.effects.isEmpty())

        val sourceLimits = MeshLimits(maxScheduledRelays = 2, maxScheduledRelaysPerSource = 1)
        val sourceEngine = MeshEngine(sourceLimits)
        val sourceAdmitted = admitUnsigned(
            sourceEngine,
            readyStateWithRelayLink(sourceEngine),
            MeshFixtures.broadcastPacket,
        )
        val occupiedBySameSource = existingRelay(sourcePeer = MeshFixtures.broadcastPacket.sender)
        val sourceFull = sourceEngine.reduce(
            sourceAdmitted.state.copy(
                scheduledRelays = SnapshotMap(
                    mapOf(occupiedBySameSource.packetId to occupiedBySameSource),
                ),
            ),
            entropyResult(sourceAdmitted.entropyRequest(), bytes("0001")),
        )
        assertEquals(1, sourceFull.state.scheduledRelays.size)
        assertTrue(sourceFull.effects.isEmpty())
    }

    @Test
    fun authenticatedDuplicateCancelsAlreadyScheduledRelay() {
        val engine = MeshEngine()
        val first = admitUnsigned(
            engine,
            readyStateWithRelayLink(engine),
            MeshFixtures.broadcastPacket,
        )
        val scheduled = engine.reduce(
            first.state,
            entropyResult(first.entropyRequest(), bytes("01f4")),
        )
        val relay = scheduled.state.scheduledRelays.values.single()
        val duplicate = admitUnsigned(engine, scheduled.state, MeshFixtures.broadcastPacket)

        assertTrue(duplicate.state.scheduledRelays.isEmpty())
        assertTrue(duplicate.effects.any {
            it is MeshEffect.Cancel && it.timerId == relay.timerId
        })
        assertTrue(duplicate.effects.none { it is MeshEffect.RequestEntropy })
    }

    @Test
    fun malformedEntropyNeverCreatesScheduledRelay() {
        val engine = MeshEngine()
        val admitted = admitUnsigned(
            engine,
            readyStateWithRelayLink(engine),
            MeshFixtures.broadcastPacket,
        )
        val entropy = admitted.entropyRequest()
        val rejected = engine.reduce(
            admitted.state,
            entropyResult(entropy, bytes("00")),
        )

        assertTrue(rejected.state.scheduledRelays.isEmpty())
        assertTrue(rejected.effects.isEmpty())
    }

    private data class RelayWrite(
        val state: MeshState,
        val write: MeshEffect.WriteLink,
    )

    private fun relayToWrite(
        engine: MeshEngine,
        packet: DecodedPacket,
        signed: Boolean,
    ): RelayWrite {
        val decoded = driveDecoded(engine, readyStateWithRelayLink(engine), packet)
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        val digested = engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(sha256Digest),
            ),
        )
        val admitted = if (signed) {
            val verify = digested.effects.filterIsInstance<MeshEffect.VerifySignature>().single()
            engine.reduce(
                digested.state,
                MeshEvent.SignatureVerified(
                    correlationId = verify.correlationId,
                    generation = MeshFixtures.generation,
                    observedAt = MeshFixtures.now,
                    result = MeshResult.Success(true),
                ),
            )
        } else {
            digested
        }
        val entropy = admitted.entropyRequest()
        val scheduled = engine.reduce(admitted.state, entropyResult(entropy, bytes("0000")))
        val schedule = scheduled.effects.filterIsInstance<MeshEffect.Schedule>().single()
        val timer = engine.reduce(
            scheduled.state,
            MeshEvent.TimerElapsed(
                correlationId = schedule.correlationId,
                generation = schedule.generation,
                observedAt = MeshFixtures.now,
                timerId = schedule.timerId,
            ),
        )
        val encode = timer.effects.filterIsInstance<MeshEffect.EncodeRelay>().single()
        val encoded = RelayEncoding.withTtl(encode.packet, encode.outgoingTtl)
        val written = engine.reduce(
            timer.state,
            MeshEvent.RelayEncoded(
                correlationId = encode.correlationId,
                generation = encode.generation,
                observedAt = MeshFixtures.now,
                packetId = encode.packetId,
                targets = encode.targets,
                result = encoded,
            ),
        )
        return RelayWrite(
            written.state,
            written.effects.filterIsInstance<MeshEffect.WriteLink>().single(),
        )
    }

    private fun driveDecoded(
        engine: MeshEngine,
        state: MeshState,
        packet: DecodedPacket,
    ): Transition<MeshState, MeshEffect> =
        engine.reduce(state, MeshFixtures.packetDecoded(packet))

    private fun existingRelay(sourcePeer: WirePeerId): ScheduledRelay {
        val packetId = PacketId.of(bytes("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
        val correlationId = CorrelationId.of("mesh:3:timer:999")
        return ScheduledRelay(
            packetId = packetId,
            sourcePeer = sourcePeer,
            ingressLink = MeshFixtures.linkA,
            packet = MeshFixtures.broadcastPacket,
            targets = SnapshotList(listOf(MeshFixtures.linkB)),
            outgoingTtl = 2u,
            correlationId = correlationId,
            timerId = TimerId.of("${correlationId.value}:relay"),
            expiresAt = MeshFixtures.now.plus(1.minutes),
        )
    }

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

    private fun readyStateWithRelayLink(engine: MeshEngine): MeshState {
        val ingress = readyState(engine)
        return engine.reduce(
            ingress,
            MeshEvent.LinkObserved(
                generation = MeshFixtures.generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.Opened(
                    MeshFixtures.linkB,
                    LinkCapabilities(maxWriteBytes = 4096, writeReady = true),
                ),
            ),
        ).state
    }

    private fun admitUnsigned(
        engine: MeshEngine,
        state: MeshState,
        packet: DecodedPacket,
        observedAt: MonotonicTime = MeshFixtures.now,
    ): Transition<MeshState, MeshEffect> {
        val decoded = engine.reduce(
            state,
            MeshFixtures.packetDecoded(packet = packet, observedAt = observedAt),
        )
        val digest = decoded.effects.filterIsInstance<MeshEffect.ComputePacketDigest>().single()
        return engine.reduce(
            decoded.state,
            MeshEvent.PacketDigestComputed(
                correlationId = digest.correlationId,
                generation = MeshFixtures.generation,
                observedAt = observedAt,
                result = MeshResult.Success(sha256Digest),
            ),
        )
    }

    private fun packetWithTtl(ttl: UByte): DecodedPacket {
        val encoded = assertIs<EncodeResult.Success>(BitchatCodec.encode(MeshFixtures.broadcastPacket.copy(ttl = ttl)))
        return decode(encoded.bytes)
    }

    private fun Transition<MeshState, MeshEffect>.entropyRequest(): MeshEffect.RequestEntropy =
        effects.filterIsInstance<MeshEffect.RequestEntropy>().single()

    private fun entropyResult(
        request: MeshEffect.RequestEntropy,
        value: Bytes,
    ): MeshEvent.EntropyProvided =
        MeshEvent.EntropyProvided(
            correlationId = request.correlationId,
            generation = request.generation,
            observedAt = MeshFixtures.now,
            packetId = request.packetId,
            source = request.source,
            packet = request.packet,
            outgoingTtl = request.outgoingTtl,
            result = MeshResult.Success(value),
        )

    private fun decode(bytes: Bytes): DecodedPacket =
        assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(bytes)).value

    private companion object {
        val sha256Digest: Bytes = MeshFixtures.fakeSha256Digest
        val localRecipientPacket: DecodedPacket = decode(
            bytes("02020301020304050607080100000002001122334455667700112233445566774142"),
        )
        val nonlocalRecipientPacket: DecodedPacket = decode(
            bytes("0202030102030405060708010000000200112233445566778899aabbccddeeff4142"),
        )
        val fragmentPacket: DecodedPacket = MeshFixtures.fragmentZeroPacket
        val signedPacket: DecodedPacket = MeshFixtures.signedPacket

        fun bytes(hex: String): Bytes = MeshFixtures.bytes(hex)

        fun decode(bytes: Bytes): DecodedPacket =
            assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(bytes)).value
    }
}
