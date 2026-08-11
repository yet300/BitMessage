package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFailureCode
import com.yet.bitmessage.engine.mesh.MeshFixtures
import com.yet.bitmessage.engine.mesh.MeshLifecycle
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshResult
import com.yet.bitmessage.engine.mesh.MeshEngine
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.FragmentPayloadCodec
import com.yet.bitmessage.protocol.bitchat.RelayEncoding
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class MeshRuntimeTest {
    @Test
    fun constructorLaunchesNothingAndClosePreventsResurrection() = runTest {
        val executor = ProtocolExecutor()
        val runtime = MeshRuntime(MeshEngine(), executor, backgroundScope, MeshLimits())

        runCurrent()
        assertTrue(executor.effects.isEmpty())
        assertNull(runtime.state.value)
        assertNull(runtime.traceEvents)

        assertEquals(
            StartResult.Started,
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now),
        )
        assertEquals(
            StartResult.AlreadyStarted,
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now),
        )
        runtime.close(MeshFixtures.now)

        assertEquals(SubmitResult.Closed, runtime.trySubmit(opened(MeshFixtures.generation)))
        assertEquals(
            StartResult.Closed,
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now),
        )
    }

    @Test
    fun boundedMailboxReportsAcceptedAndBackpressuredWithoutSuspending() = runTest {
        val limits = MeshLimits(eventMailboxCapacity = 1)
        val runtime = MeshRuntime(MeshEngine(limits), ProtocolExecutor(), backgroundScope, limits)
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val results = List(8) { runtime.trySubmit(opened(runtime.generation)) }
        assertTrue(results.any { it == SubmitResult.Accepted })
        assertTrue(results.any { it == SubmitResult.Backpressured })

        runCurrent()
        runtime.close(MeshFixtures.now)
        assertEquals(SubmitResult.Closed, runtime.trySubmit(opened(runtime.generation)))
    }

    @Test
    fun oneActorSerializesTransitionsAndOneWorkerPreservesEffectOrder() = runTest {
        val executor = ProtocolExecutor()
        val runtime = MeshRuntime(MeshEngine(), executor, backgroundScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
        runCurrent()
        assertEquals(
            SubmitResult.Accepted,
            runtime.trySubmit(
                MeshEvent.LinkObserved(
                    generation = runtime.generation,
                    observedAt = MeshFixtures.now,
                    event = LinkEvent.PayloadReceived(
                        MeshFixtures.linkA,
                        MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(
            listOf(
                MeshEffect.DecodePacket::class,
                MeshEffect.ComputePacketDigest::class,
                MeshEffect.PublishPublicPayload::class,
                MeshEffect.RequestEntropy::class,
            ),
            executor.effects.map { it::class },
        )
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        assertTrue(assertNotNull(runtime.state.value).pendingAdmissions.isEmpty())
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun cancelledAdmissionTimerNeverFiresAfterSuccessfulAdmission() = runTest {
        val executor = ProtocolExecutor()
        val runtime = MeshRuntime(MeshEngine(), executor, backgroundScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        runtime.trySubmit(opened(runtime.generation))
        runCurrent()
        runtime.trySubmit(
            MeshEvent.LinkObserved(
                generation = runtime.generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                ),
            ),
        )
        runCurrent()
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)

        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        runtime.close(MeshFixtures.now.plus(1.minutes))
    }

    @Test
    fun traceBackpressureDropsObservablyWithoutBlockingState() = runTest {
        val limits = MeshLimits(traceBufferCapacity = 1)
        val runtime = MeshRuntime(MeshEngine(limits), ProtocolExecutor(), backgroundScope, limits)
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertNotNull(runtime.traceEvents)

        repeat(6) {
            while (runtime.trySubmit(opened(runtime.generation)) == SubmitResult.Backpressured) {
                runCurrent()
            }
            runCurrent()
        }

        assertTrue(runtime.droppedTraceCount.value > 0)
        assertTrue(assertNotNull(runtime.state.value).links.isNotEmpty())
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun stopClearsTransientStateAndRestartRetainsOnlyLiveDedupWithNewGeneration() = runTest {
        val executor = ProtocolExecutor()
        val runtime = MeshRuntime(MeshEngine(), executor, backgroundScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        val oldGeneration = runtime.generation
        runtime.trySubmit(opened(oldGeneration))
        runCurrent()
        runtime.trySubmit(
            MeshEvent.LinkObserved(
                generation = oldGeneration,
                observedAt = MeshFixtures.now,
                event = LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    MeshFixtures.broadcastPacket.rawPacket.wireBytes,
                ),
            ),
        )
        runCurrent()
        val oldDecode = assertIs<MeshEffect.DecodePacket>(executor.effects.first())
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)

        runtime.stop(MeshFixtures.now.plus(1.minutes))
        val stopped = assertNotNull(runtime.state.value)
        assertEquals(MeshLifecycle.STOPPED, stopped.lifecycle)
        assertEquals(1, stopped.admittedPackets.size)
        assertTransientStateIsEmpty(stopped)
        assertEquals(SubmitResult.Closed, runtime.trySubmit(opened(oldGeneration)))

        assertEquals(
            StartResult.Started,
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now.plus(2.minutes)),
        )
        assertEquals(oldGeneration.next(), runtime.generation)
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        assertNotNull(assertNotNull(runtime.state.value).dedupExpiryTimer)

        val beforeStale = runtime.state.value
        assertEquals(
            SubmitResult.Accepted,
            runtime.trySubmit(
                MeshEvent.PacketDecoded(
                    correlationId = oldDecode.correlationId,
                    generation = oldDecode.generation,
                    observedAt = MeshFixtures.now.plus(2.minutes),
                    source = oldDecode.source,
                    result = DecodeResult.Success(MeshFixtures.broadcastPacket),
                ),
            ),
        )
        runCurrent()
        assertEquals(beforeStale, runtime.state.value)

        runtime.stop(MeshFixtures.now.plus(6.minutes))
        assertTrue(assertNotNull(runtime.state.value).admittedPackets.isEmpty())
        assertTransientStateIsEmpty(assertNotNull(runtime.state.value))
        runtime.close(MeshFixtures.now.plus(6.minutes))
    }

    @Test
    fun cancellationIsRethrownWhileOrdinaryFailuresBecomeTypedEvents() = runTest {
        val effect = MeshEffect.DecodePacket(
            correlationId = com.yet.bitmessage.foundation.CorrelationId.of("mesh:3:decode:0"),
            generation = MeshFixtures.generation,
            source = com.yet.bitmessage.engine.mesh.PacketSource.Link(MeshFixtures.linkA),
            bytes = MeshFixtures.broadcastPacket.rawPacket.wireBytes,
        )
        val cancellation = CancellationException("cancelled")
        var cancellationObserved = false
        try {
            executeEffect(
                executor = MeshEffectExecutor { throw cancellation },
                effect = effect,
                observedAt = MeshFixtures.now,
            )
        } catch (caught: CancellationException) {
            cancellationObserved = caught === cancellation
        }
        assertTrue(cancellationObserved)

        val failure = executeEffect(
            executor = MeshEffectExecutor { error("redacted failure") },
            effect = effect,
            observedAt = MeshFixtures.now,
        )
        val failed = assertIs<MeshEvent.EffectFailed>(failure)
        assertEquals(MeshFailureCode.EFFECT_EXECUTION_FAILED, failed.code)
        assertEquals(effect.correlationId, failed.correlationId)
    }

    private class ProtocolExecutor : MeshEffectExecutor {
        val effects = mutableListOf<MeshEffect>()

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            effects += effect
            return when (effect) {
                is MeshEffect.DecodePacket -> MeshEvent.PacketDecoded(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    source = effect.source,
                    result = BitchatCodec.decode(effect.bytes),
                )
                is MeshEffect.ComputePacketDigest -> MeshEvent.PacketDigestComputed(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    result = MeshResult.Success(sha256Digest),
                )
                is MeshEffect.VerifySignature -> MeshEvent.SignatureVerified(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    result = MeshResult.Success(true),
                )
                is MeshEffect.DecodeFragmentPayload -> MeshEvent.FragmentPayloadDecoded(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    packetId = effect.packetId,
                    source = effect.source,
                    sender = effect.sender,
                    result = FragmentPayloadCodec.decode(effect.payload),
                )
                is MeshEffect.EncodeRelay -> MeshEvent.RelayEncoded(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    packetId = effect.packetId,
                    targets = effect.targets,
                    result = RelayEncoding.withTtl(effect.packet, effect.outgoingTtl),
                )
                is MeshEffect.RequestEntropy -> MeshEvent.EntropyProvided(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    packetId = effect.packetId,
                    source = effect.source,
                    packet = effect.packet,
                    outgoingTtl = effect.outgoingTtl,
                    result = MeshResult.Success(Bytes.copyOf(byteArrayOf(0))),
                )
                is MeshEffect.WriteLink -> MeshEvent.LinkCompleted(
                    correlationId = effect.correlationId,
                    generation = effect.generation,
                    observedAt = MeshFixtures.now,
                    result = LinkResult.Written(
                        effect.command.linkId,
                        effect.command.correlationId,
                        effect.command.generation,
                    ),
                )
                is MeshEffect.CloseLink,
                is MeshEffect.PublishPublicPayload,
                is MeshEffect.Schedule,
                is MeshEffect.Cancel,
                -> null
            }
        }
    }

    private fun assertTransientStateIsEmpty(state: com.yet.bitmessage.engine.mesh.MeshState) {
        assertTrue(state.links.isEmpty())
        assertTrue(state.provisionalBindings.isEmpty())
        assertTrue(state.pendingAdmissions.isEmpty())
        assertTrue(state.pendingFragmentDecodes.isEmpty())
        assertTrue(state.fragmentStreams.isEmpty())
        assertTrue(state.routeObservations.isEmpty())
        assertTrue(state.scheduledRelays.isEmpty())
        assertTrue(state.pendingRelayEntropy.isEmpty())
        assertTrue(state.pendingRelayEncodes.isEmpty())
        assertTrue(state.pendingLinkWrites.isEmpty())
        assertEquals(0, state.aggregatePendingBytes)
        assertEquals(0, state.aggregateFragmentBytes)
    }

    private companion object {
        val sha256Digest: Bytes = MeshFixtures.bytes(
            "25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda",
        )

        fun opened(generation: com.yet.bitmessage.foundation.Generation): MeshEvent.LinkObserved =
            MeshEvent.LinkObserved(
                generation = generation,
                observedAt = MeshFixtures.now,
                event = LinkEvent.Opened(
                    MeshFixtures.linkA,
                    LinkCapabilities(maxWriteBytes = 4096, writeReady = true),
                ),
            )
    }
}
