package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEngine
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFixtures
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshResult
import com.yet.bitmessage.engine.mesh.MeshState
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Engine
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MeshRuntimeAcknowledgementTest {
    @Test
    fun submitAndAwaitReturnsOnlyAfterTransitionAndEffectsAreRegistered() = runTest {
        val executor = BlockingDigestExecutor()
        val runtime = runtime(executor = executor)
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))

        val submitting = backgroundScope.async {
            runtime.submitAndAwait(MeshFixtures.packetDecoded(eventGeneration = runtime.generation))
        }
        executor.started.await()

        assertEquals(SubmitResult.Accepted, submitting.await())
        assertEquals(1, assertNotNull(runtime.state.value).pendingAdmissions.size)
        assertTrue(assertNotNull(runtime.state.value).admittedPackets.isEmpty())

        executor.release.complete(Unit)
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(32))
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun acknowledgementDoesNotWaitForAsynchronousCompletion() = runTest {
        val executor = RecordingDigestExecutor()
        val runtime = runtime(executor = executor)
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))

        assertEquals(
            SubmitResult.Accepted,
            runtime.submitAndAwait(MeshFixtures.packetDecoded(eventGeneration = runtime.generation)),
        )
        val digest = executor.recorded.receive()
        assertEquals(1, assertNotNull(runtime.state.value).pendingAdmissions.size)
        assertTrue(assertNotNull(runtime.state.value).admittedPackets.isEmpty())

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(digestResult(digest)))
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(32))
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun immediateResultChainsReachACausalFenceWithoutPolling() = runTest {
        val runtime = runtime(executor = ImmediateExecutor())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        assertEquals(
            SubmitResult.Accepted,
            runtime.submitAndAwait(MeshFixtures.packetDecoded(eventGeneration = runtime.generation)),
        )

        val quiescence = assertIs<RuntimeQuiescenceResult.Quiescent>(
            runtime.awaitImmediateQuiescence(32),
        )
        assertTrue(quiescence.processedEffects > 0)
        assertEquals(1, assertNotNull(runtime.state.value).admittedPackets.size)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun immediateEffectBoundStopsACausalCycle() = runTest {
        val releaseLoop = CompletableDeferred<Unit>()
        val runtime = runtime(
            engine = PublicationLoopEngine(),
            executor = LoopingExecutor(releaseLoop),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        releaseLoop.complete(Unit)

        assertEquals(
            RuntimeQuiescenceResult.LimitExceeded(4),
            runtime.awaitImmediateQuiescence(4),
        )
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun closedLifecycleTerminatesEverySuspendingRuntimeApi() = runTest {
        val runtime = runtime(executor = ImmediateExecutor())

        assertEquals(SubmitResult.Closed, runtime.submitAndAwait(opened(MeshFixtures.generation)))
        assertEquals(
            SubmitResult.Closed,
            runtime.submitAndAwait(openedLink(), MeshFixtures.now),
        )
        assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(1))

        runtime.close(MeshFixtures.now)
        assertEquals(SubmitResult.Closed, runtime.submitAndAwait(opened(MeshFixtures.generation)))
        assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(1))
        assertEquals(
            StartResult.Closed,
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now),
        )
    }

    @Test
    fun structuralDecodeRejectionCannotMutateStateOrPendingAdmission() = runTest {
        val runtime = runtime(executor = ImmediateExecutor())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(
            SubmitResult.Accepted,
            runtime.submitAndAwait(openedLink(), MeshFixtures.now),
        )
        val before = runtime.state.value

        assertEquals(
            SubmitResult.StructuralDecodeRejected(DecodeError.TRUNCATED),
            runtime.submitAndAwait(
                LinkEvent.PayloadReceived(
                    MeshFixtures.linkA,
                    Bytes.copyOf(byteArrayOf(2)),
                ),
                MeshFixtures.now,
            ),
        )

        assertEquals(before, runtime.state.value)
        assertTrue(assertNotNull(runtime.state.value).pendingAdmissions.isEmpty())
        assertEquals(1L, runtime.structuralDecodeRejectionCount.value)
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun boundedMailboxReturnsBackpressuredWithoutSuspendingPastCapacity() = runTest {
        val release = CompletableDeferred<Unit>()
        val executor = BlockingFirstPublicationExecutor(release)
        val limits = MeshLimits(
            eventMailboxCapacity = 1,
            effectQueueCapacity = 9,
            maxRelayFanout = 1,
        )
        val runtime = runtime(
            engine = BurstEngine(effectCount = 11),
            executor = executor,
            limits = limits,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
        executor.started.await()

        val admittedToMailbox = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        assertEquals(
            SubmitResult.Backpressured,
            runtime.submitAndAwait(opened(runtime.generation)),
        )

        release.complete(Unit)
        assertEquals(SubmitResult.Accepted, admittedToMailbox.await())
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun reducerFailureIsPropagatedAndCannotStrandAcknowledgement() = runTest {
        val reducerFailure = IllegalStateException("runtime reducer failure")
        val uncaught = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> uncaught.complete(failure) }
        val parentJob = Job()
        val ownedScope = CoroutineScope(backgroundScope.coroutineContext + parentJob + handler)
        val delegate = MeshEngine()
        val throwingEngine = object : Engine<MeshState, MeshEvent, MeshEffect> {
            override fun reduce(
                state: MeshState,
                event: MeshEvent,
            ): Transition<MeshState, MeshEffect> =
                if (event is MeshEvent.LinkObserved) throw reducerFailure else delegate.reduce(state, event)
        }
        val runtime = MeshRuntime(throwingEngine, ImmediateExecutor(), ownedScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val propagated = assertFailsWith<IllegalStateException> {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        assertEquals(reducerFailure.message, propagated.message)
        assertSame(reducerFailure, uncaught.await())
        assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(8))
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun immediateEffectBoundMustBePositive() = runTest {
        val runtime = runtime(executor = ImmediateExecutor())

        assertFailsWith<IllegalArgumentException> {
            runtime.awaitImmediateQuiescence(0)
        }
        assertFailsWith<IllegalArgumentException> {
            runtime.awaitImmediateQuiescence(-1)
        }
    }

    private fun TestScope.runtime(
        engine: Engine<MeshState, MeshEvent, MeshEffect> = MeshEngine(),
        executor: MeshEffectExecutor,
        limits: MeshLimits = MeshLimits(),
    ): MeshRuntime = MeshRuntime(engine, executor, backgroundScope, limits)

    private class BlockingDigestExecutor : MeshEffectExecutor {
        val started = CompletableDeferred<MeshEffect.ComputePacketDigest>()
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(effect: MeshEffect): MeshEvent? =
            when (effect) {
                is MeshEffect.ComputePacketDigest -> {
                    started.complete(effect)
                    release.await()
                    digestResult(effect)
                }
                else -> null
            }
    }

    private class RecordingDigestExecutor : MeshEffectExecutor {
        val recorded = Channel<MeshEffect.ComputePacketDigest>(1)

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect is MeshEffect.ComputePacketDigest) recorded.send(effect)
            return null
        }
    }

    private class ImmediateExecutor : MeshEffectExecutor {
        override suspend fun execute(effect: MeshEffect): MeshEvent? =
            when (effect) {
                is MeshEffect.ComputePacketDigest -> digestResult(effect)
                is MeshEffect.PublishPublicPayload -> null
                else -> null
            }
    }

    private class LoopingExecutor(
        private val release: CompletableDeferred<Unit>,
    ) : MeshEffectExecutor {
        override suspend fun execute(effect: MeshEffect): MeshEvent? =
            when (effect) {
                is MeshEffect.PublishPublicPayload -> {
                    release.await()
                    opened(effect.generation)
                }
                else -> null
            }
    }

    private class BlockingFirstPublicationExecutor(
        private val release: CompletableDeferred<Unit>,
    ) : MeshEffectExecutor {
        val started = CompletableDeferred<Unit>()
        private var blocked = false

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect is MeshEffect.PublishPublicPayload && !blocked) {
                blocked = true
                started.complete(Unit)
                release.await()
            }
            return null
        }
    }

    private class PublicationLoopEngine : Engine<MeshState, MeshEvent, MeshEffect> {
        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> = Transition(
            state = state.copy(observedAt = event.observedAt),
            effects = listOf(publication(event.generation)),
        )
    }

    private class BurstEngine(
        private val effectCount: Int,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        private val delegate = MeshEngine()

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> Transition(
                    state = state,
                    effects = List(effectCount) { publication(event.generation) },
                )
            }
    }

    private companion object {
        fun opened(generation: Generation): MeshEvent.LinkObserved = MeshEvent.LinkObserved(
            generation = generation,
            observedAt = MeshFixtures.now,
            event = openedLink(),
        )

        fun openedLink(): LinkEvent.Opened = LinkEvent.Opened(
            MeshFixtures.linkA,
            LinkCapabilities(maxWriteBytes = 4096, writeReady = true),
        )

        fun digestResult(effect: MeshEffect.ComputePacketDigest): MeshEvent.PacketDigestComputed =
            MeshEvent.PacketDigestComputed(
                correlationId = effect.correlationId,
                generation = effect.generation,
                observedAt = MeshFixtures.now,
                result = MeshResult.Success(MeshFixtures.fakeSha256Digest),
            )

        fun publication(generation: Generation): MeshEffect.PublishPublicPayload =
            MeshEffect.PublishPublicPayload(
                correlationId = CorrelationId.of("runtime-fence-loop"),
                generation = generation,
                packetId = PacketId.of(Bytes.copyOf(ByteArray(PacketId.BYTE_SIZE))),
                sender = MeshFixtures.localPeer,
                ingressLink = MeshFixtures.linkA,
                timestamp = 0u,
                payload = Bytes.copyOf(byteArrayOf(1)),
            )
    }
}
