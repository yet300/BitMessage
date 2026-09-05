package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEngine
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFixtures
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshLifecycle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MeshRuntimeAcknowledgementTest {
    @Test
    fun quiescenceWaitsUntilTheActorAcceptsAnExecutedEffectsSettlement() = runTest {
        val engine = SettlementWatermarkEngine()
        val executor = SettlementWatermarkExecutor()
        val parentJob = Job()
        val runtimeDispatcher = Dispatchers.Default.limitedParallelism(2)
        val ownedScope = CoroutineScope(runtimeDispatcher + parentJob)
        val runtime = MeshRuntime(engine, executor, ownedScope, MeshLimits())
        val barrierDispatcher = Dispatchers.Default.limitedParallelism(1)

        try {
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
            assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
            executor.firstStarted.await()

            val quiescence = backgroundScope.async(
                context = barrierDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                runtime.awaitImmediateQuiescence(8)
            }
            assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
            engine.firstBlockEntered.await()

            executor.releaseFirst.complete(Unit)
            executor.firstReturning.await()
            withContext(runtimeDispatcher) {
                // The actor occupies one dispatcher permit. This marker can use the other only after
                // the worker has returned from E1 and suspended while delivering its settlement.
            }
            engine.releaseFirstBlock.complete(Unit)
            executor.secondStarted.await()
            assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
            engine.secondBlockEntered.await()
            withContext(barrierDispatcher) {
                // This task is queued after the fence continuation. Completing it proves that the
                // barrier has sent EffectCount while the actor is still held in the second block.
            }
            executor.releaseSecond.complete(Unit)
            executor.secondReturning.await()
            withContext(runtimeDispatcher) {
                // As above, completion proves E2 has advanced to its blocked settlement delivery.
            }
            engine.releaseSecondBlock.complete(Unit)
            engine.secondResultReductionEntered.await()

            assertNull(
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(1.seconds) { quiescence.await() }
                },
                "The barrier reported quiescence before the actor committed E2's settlement.",
            )

            engine.releaseSecondResultReduction.complete(Unit)
            assertEquals(
                RuntimeQuiescenceResult.Quiescent(processedEffects = 2),
                withContext(Dispatchers.Default) {
                    withTimeout(1.seconds) { quiescence.await() }
                },
            )
        } finally {
            executor.releaseFirst.complete(Unit)
            executor.releaseSecond.complete(Unit)
            engine.releaseFirstBlock.complete(Unit)
            engine.releaseSecondBlock.complete(Unit)
            engine.releaseSecondResultReduction.complete(Unit)
            runtime.close(MeshFixtures.now)
            parentJob.cancel()
        }
    }

    @Test
    fun fastImmediateCycleCannotStarveAnEligibleFence() = runTest {
        val executor = FenceRaceExecutor()
        val engine = FenceRaceEngine()
        val parentJob = Job()
        val ownedScope = CoroutineScope(Dispatchers.Default + parentJob)
        val runtime = MeshRuntime(
            engine,
            executor,
            ownedScope,
            MeshLimits(),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        executor.fifthStarted.await()
        assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
        engine.blockingReductionEntered.await()

        val fence = backgroundScope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            runtime.awaitImmediateQuiescence(4)
        }
        executor.releaseFifth.complete(Unit)
        executor.fifthReturning.await()
        engine.releaseBlockingReduction.complete(Unit)
        executor.sixthStarted.await()

        val result = try {
            withContext(Dispatchers.Default) {
                withTimeout(1.seconds) { fence.await() }
            }
        } finally {
            runtime.close(MeshFixtures.now)
            parentJob.cancel()
        }
        assertEquals(RuntimeQuiescenceResult.LimitExceeded(4), result)
    }

    @Test
    fun validResultTransitionWaitsForMomentaryLedgerPressure() = runTest {
        val executor = BackedUpImmediateExecutor(immediateResults = 1)
        val runtime = runtime(
            engine = SequencedBurstEngine(effectCounts = listOf(9, 9, 2)),
            executor = executor,
            limits = MeshLimits(
                effectQueueCapacity = 9,
                maxRelayFanout = 1,
            ),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val firstSubmission = backgroundScope.async {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        executor.firstStarted.await()
        assertEquals(SubmitResult.Accepted, firstSubmission.await())
        assertEquals(
            SubmitResult.Accepted,
            runtime.submitAndAwait(opened(runtime.generation)),
        )

        executor.releaseFirst.complete(Unit)

        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 20),
            runtime.awaitImmediateQuiescence(32),
        )
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun maximumResultTransitionIgnoresCancelledFencePressure() = runTest {
        val executor = BackedUpImmediateExecutor(immediateResults = 1)
        val runtime = runtime(
            engine = SequencedBurstEngine(effectCounts = listOf(9, 9, 9)),
            executor = executor,
            limits = MeshLimits(
                effectQueueCapacity = 9,
                maxRelayFanout = 1,
            ),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val firstSubmission = backgroundScope.async {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        executor.firstStarted.await()
        assertEquals(SubmitResult.Accepted, firstSubmission.await())
        assertEquals(
            SubmitResult.Accepted,
            runtime.submitAndAwait(opened(runtime.generation)),
        )
        val cancelledFence = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runtime.awaitImmediateQuiescence(32)
        }
        cancelledFence.cancelAndJoin()

        executor.releaseFirst.complete(Unit)

        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 27),
            runtime.awaitImmediateQuiescence(32),
        )
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun stopPreemptsAStagedResultAndCancelsTheBlockedWorker() = runTest {
        val executor = StagedStopExecutor()
        val runtime = runtime(
            engine = SequencedBurstEngine(effectCounts = listOf(9, 9, 9)),
            executor = executor,
            limits = MeshLimits(effectQueueCapacity = 9, maxRelayFanout = 1),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val firstSubmission = backgroundScope.async {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        executor.firstStarted.await()
        assertEquals(SubmitResult.Accepted, firstSubmission.await())
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        executor.releaseFirst.complete(Unit)
        executor.secondStarted.await()

        assertEquals(
            StopResult.Stopped,
            withTimeout(1.seconds) { runtime.stop(MeshFixtures.now) },
        )
        executor.secondCancelled.await()
        assertEquals(MeshLifecycle.STOPPED, assertNotNull(runtime.state.value).lifecycle)
    }

    @Test
    fun boundedPendingEffectsCannotDeadlockRendezvousImmediateResults() = runTest {
        val executor = BackedUpImmediateExecutor(immediateResults = 3)
        val runtime = runtime(
            engine = ResultBurstEngine(
                initialEffects = 9,
                resultEffects = 2,
            ),
            executor = executor,
            limits = MeshLimits(
                effectQueueCapacity = 9,
                maxRelayFanout = 1,
            ),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val submitting = backgroundScope.async {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        executor.firstStarted.await()
        assertEquals(SubmitResult.Accepted, submitting.await())

        executor.releaseFirst.complete(Unit)
        executor.thirdStarted.await()

        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 15),
            runtime.awaitImmediateQuiescence(32),
        )
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun overflowingInternalResultFailsWithoutPartiallyPublishingItsTransition() = runTest {
        val executor = BackedUpImmediateExecutor(immediateResults = 1)
        val engine = OverflowingResultEngine(
            initialEffects = 9,
            resultEffects = 10,
        )
        val uncaught = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> uncaught.complete(failure) }
        val parentJob = Job()
        val ownedScope = CoroutineScope(backgroundScope.coroutineContext + parentJob + handler)
        val runtime = MeshRuntime(
            engine = engine,
            executor = executor,
            parentScope = ownedScope,
            limits = MeshLimits(
                effectQueueCapacity = 9,
                maxRelayFanout = 1,
            ),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        val submitting = backgroundScope.async {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        executor.firstStarted.await()
        assertEquals(SubmitResult.Accepted, submitting.await())

        executor.releaseFirst.complete(Unit)
        engine.resultReduced.await()

        assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(32))
        assertEquals(MeshLifecycle.RUNNING, assertNotNull(runtime.state.value).lifecycle)
        assertEquals(
            "Pending effect capacity exceeded: required=10, available=9, maximum=9.",
            uncaught.await().message,
        )
        runtime.close(MeshFixtures.now)
        parentJob.cancel()
    }

    @Test
    fun executorCancellationClosesRuntimeInsteadOfLeavingActorAccepting() = runTest {
        val executor = CancellingExecutor()
        val runtime = runtime(
            engine = BurstEngine(effectCount = 1),
            executor = executor,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        executor.attempted.await()
        assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(8))

        assertEquals(SubmitResult.Closed, runtime.submitAndAwait(opened(runtime.generation)))
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun reducerCancellationShutsDownEffectWorkerAndAcknowledgementWaiters() = runTest {
        val executor = UntilCancelledExecutor()
        val runtime = runtime(
            engine = CancellingReducerEngine(),
            executor = executor,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        executor.started.await()

        assertFailsWith<CancellationException> {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        executor.cancelled.await()
        assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(8))
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun stopCannotPublishStoppedBeforeCancellingActorFinishesItsLastTransition() = runTest {
        val releaseReducer = CompletableDeferred<Unit>()
        val engine = HeldReducerEngine(releaseReducer)
        val parentJob = Job()
        val ownedScope = CoroutineScope(Dispatchers.Default + parentJob)
        val runtime = MeshRuntime(engine, ImmediateExecutor(), ownedScope, MeshLimits())
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)

        assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
        engine.entered.await()
        parentJob.cancel()

        val stopping = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runtime.stop(MeshFixtures.now)
        }
        releaseReducer.complete(Unit)

        assertEquals(StopResult.Stopped, stopping.await())
        assertEquals(MeshLifecycle.STOPPED, assertNotNull(runtime.state.value).lifecycle)
    }

    @Test
    fun callerCancelledStopStillCommitsStoppedAfterActorTermination() = runTest {
        val engine = CallerCancelledTeardownEngine()
        val parentJob = Job()
        val callerDispatcher = Dispatchers.Default.limitedParallelism(1)
        val runtime = MeshRuntime(
            engine,
            ImmediateExecutor(),
            CoroutineScope(Dispatchers.Default + parentJob),
            MeshLimits(),
        )

        try {
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
            assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
            assertTrue(assertNotNull(runtime.state.value).links.isNotEmpty())
            assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
            engine.heldReductionEntered.await()

            val stopping = backgroundScope.async(
                context = callerDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                runtime.stop(MeshFixtures.now)
            }
            assertTrue(!stopping.isCompleted)
            assertEquals(SubmitResult.Backpressured, runtime.trySubmit(opened(runtime.generation)))

            val cancellation = CancellationException("cancel stop while control awaits actor")
            stopping.cancel(cancellation)
            withContext(callerDispatcher) {
                // Queued after cancellation; this runs only once shutdown has cancelled the runtime
                // supervisor and suspended joining the held actor.
            }
            engine.releaseHeldReduction.complete(Unit)
            val propagated = assertFailsWith<CancellationException> {
                withContext(Dispatchers.Default) {
                    withTimeout(1.seconds) { stopping.await() }
                }
            }

            assertEquals(cancellation.message, propagated.message)
            assertStoppedAndTransientStateIsEmpty(assertNotNull(runtime.state.value))
            assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(1))
            assertEquals(StopResult.AlreadyStopped, runtime.stop(MeshFixtures.now))
            assertEquals(StartResult.Started, runtime.start(MeshFixtures.localPeer, MeshFixtures.now))
        } finally {
            engine.releaseHeldReduction.complete(Unit)
            runtime.close(MeshFixtures.now)
            parentJob.cancel()
        }
    }

    @Test
    fun callerCancelledCloseStillCommitsStoppedAndRemainsPermanentlyClosed() = runTest {
        val engine = CallerCancelledTeardownEngine()
        val parentJob = Job()
        val callerDispatcher = Dispatchers.Default.limitedParallelism(1)
        val runtime = MeshRuntime(
            engine,
            ImmediateExecutor(),
            CoroutineScope(Dispatchers.Default + parentJob),
            MeshLimits(),
        )

        try {
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
            assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
            assertTrue(assertNotNull(runtime.state.value).links.isNotEmpty())
            assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
            engine.heldReductionEntered.await()

            val closing = backgroundScope.async(
                context = callerDispatcher,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                runtime.close(MeshFixtures.now)
            }
            assertTrue(!closing.isCompleted)
            assertEquals(SubmitResult.Backpressured, runtime.trySubmit(opened(runtime.generation)))

            val cancellation = CancellationException("cancel close while control awaits actor")
            closing.cancel(cancellation)
            withContext(callerDispatcher) {
                // Queued after cancellation; this runs only once shutdown has cancelled the runtime
                // supervisor and suspended joining the held actor.
            }
            engine.releaseHeldReduction.complete(Unit)
            val propagated = assertFailsWith<CancellationException> {
                withContext(Dispatchers.Default) {
                    withTimeout(1.seconds) { closing.await() }
                }
            }

            assertEquals(cancellation.message, propagated.message)
            assertStoppedAndTransientStateIsEmpty(assertNotNull(runtime.state.value))
            assertEquals(RuntimeQuiescenceResult.Closed, runtime.awaitImmediateQuiescence(1))
            assertEquals(StopResult.AlreadyStopped, runtime.stop(MeshFixtures.now))
            assertEquals(StartResult.Closed, runtime.start(MeshFixtures.localPeer, MeshFixtures.now))
        } finally {
            engine.releaseHeldReduction.complete(Unit)
            runtime.close(MeshFixtures.now)
            parentJob.cancel()
        }
    }

    @Test
    fun startupThrowableCleansRunContextAndAllowsSubsequentStart() = runTest {
        val startupFailure = AssertionError("startup reducer failure")
        val engine = OneShotStartupThrowableEngine(startupFailure)
        val uncaught = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> uncaught.complete(failure) }
        val parentJob = Job()
        val ownedScope = CoroutineScope(backgroundScope.coroutineContext + parentJob + handler)
        val runtime = MeshRuntime(engine, ImmediateExecutor(), ownedScope, MeshLimits())

        val propagated = assertFailsWith<AssertionError> {
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        }
        assertEquals(startupFailure.message, propagated.message)
        assertSame(startupFailure, uncaught.await())

        assertEquals(
            StartResult.Started,
            runtime.start(MeshFixtures.localPeer, MeshFixtures.now),
        )
        runtime.close(MeshFixtures.now)
        parentJob.cancel()
    }

    @Test
    fun cancelledAcknowledgementWaiterDoesNotCorruptEffectAccounting() = runTest {
        val release = CompletableDeferred<Unit>()
        val executor = BlockingFirstPublicationExecutor(release)
        val engine = CountingBurstEngine(effectCount = 9)
        val runtime = runtime(
            engine = engine,
            executor = executor,
            limits = MeshLimits(
                eventMailboxCapacity = 1,
                effectQueueCapacity = 9,
                maxRelayFanout = 1,
            ),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
        executor.started.await()
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))

        val cancelledSubmit = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runtime.submitAndAwait(opened(runtime.generation))
        }
        cancelledSubmit.cancelAndJoin()
        release.complete(Unit)
        engine.thirdReduced.await()

        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 27),
            runtime.awaitImmediateQuiescence(32),
        )
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun cancelledFenceWaiterDoesNotCorruptEffectAccounting() = runTest {
        val release = CompletableDeferred<Unit>()
        val runtime = runtime(
            engine = BurstEngine(effectCount = 1),
            executor = BlockingFirstPublicationExecutor(release),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))

        val cancelledFence = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runtime.awaitImmediateQuiescence(8)
        }
        cancelledFence.cancelAndJoin()
        release.complete(Unit)

        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 1),
            runtime.awaitImmediateQuiescence(8),
        )
        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 0),
            runtime.awaitImmediateQuiescence(8),
        )
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun repeatedFencesReportExactDeltasAndResetAcrossRestart() = runTest {
        val runtime = runtime(
            engine = SequencedBurstEngine(effectCounts = listOf(4, 2, 3)),
            executor = ImmediateExecutor(),
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        val firstGeneration = runtime.generation

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(firstGeneration)))
        assertEquals(
            RuntimeQuiescenceResult.LimitExceeded(maximumEffects = 2),
            runtime.awaitImmediateQuiescence(2),
        )
        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 0),
            runtime.awaitImmediateQuiescence(1),
        )

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(firstGeneration)))
        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 2),
            runtime.awaitImmediateQuiescence(2),
        )
        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 0),
            runtime.awaitImmediateQuiescence(1),
        )

        assertEquals(StopResult.Stopped, runtime.stop(MeshFixtures.now))
        assertEquals(StartResult.Started, runtime.start(MeshFixtures.localPeer, MeshFixtures.now))
        assertEquals(firstGeneration.next(), runtime.generation)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        assertEquals(
            RuntimeQuiescenceResult.Quiescent(processedEffects = 3),
            runtime.awaitImmediateQuiescence(3),
        )
        runtime.close(MeshFixtures.now)
    }

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
    fun finiteImmediateChainThatSettlesBeyondBoundReturnsLimitExceeded() = runTest {
        val executor = FiniteLoopingExecutor(
            immediateResults = 3,
            terminalPublications = 5,
        )
        val runtime = runtime(
            engine = PublicationLoopEngine(),
            executor = executor,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(runtime.generation)))
        executor.settled.await()

        assertEquals(
            RuntimeQuiescenceResult.LimitExceeded(2),
            runtime.awaitImmediateQuiescence(2),
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
            engine = BurstEngine(effectCount = 9),
            executor = executor,
            limits = limits,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        assertEquals(SubmitResult.Accepted, runtime.trySubmit(opened(runtime.generation)))
        executor.started.await()
        assertEquals(
            SubmitResult.Accepted,
            runtime.submitAndAwait(opened(runtime.generation)),
        )

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

    private class BackedUpImmediateExecutor(
        private val immediateResults: Int,
    ) : MeshEffectExecutor {
        val firstStarted = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        private var executions = 0

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect !is MeshEffect.PublishPublicPayload) return null
            executions += 1
            when (executions) {
                1 -> {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                3 -> thirdStarted.complete(Unit)
            }
            return if (executions <= immediateResults) opened(effect.generation) else null
        }
    }

    private class CancellingExecutor : MeshEffectExecutor {
        val attempted = CompletableDeferred<Unit>()

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect is MeshEffect.PublishPublicPayload) {
                attempted.complete(Unit)
                throw CancellationException("executor cancellation")
            }
            return null
        }
    }

    private class StagedStopExecutor : MeshEffectExecutor {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondCancelled = CompletableDeferred<Unit>()
        private var executions = 0

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect !is MeshEffect.PublishPublicPayload) return null
            executions += 1
            return when (executions) {
                1 -> {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    opened(effect.generation)
                }
                2 -> {
                    secondStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        secondCancelled.complete(Unit)
                    }
                }
                else -> null
            }
        }
    }

    private class UntilCancelledExecutor : MeshEffectExecutor {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect !is MeshEffect.PublishPublicPayload) return null
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
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

    private class FenceRaceExecutor : MeshEffectExecutor {
        val fifthStarted = CompletableDeferred<Unit>()
        val releaseFifth = CompletableDeferred<Unit>()
        val fifthReturning = CompletableDeferred<Unit>()
        val sixthStarted = CompletableDeferred<Unit>()
        private var processed = 0

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect !is MeshEffect.PublishPublicPayload) return null
            processed += 1
            return when (processed) {
                in 1..4 -> null
                5 -> {
                    fifthStarted.complete(Unit)
                    releaseFifth.await()
                    fifthReturning.complete(Unit)
                    opened(effect.generation)
                }
                else -> {
                    sixthStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }
    }

    private class SettlementWatermarkExecutor : MeshEffectExecutor {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstReturning = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val secondReturning = CompletableDeferred<Unit>()
        private var executions = 0

        override suspend fun execute(effect: MeshEffect): MeshEvent? {
            if (effect !is MeshEffect.PublishPublicPayload) return null
            executions += 1
            return when (executions) {
                1 -> {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    firstReturning.complete(Unit)
                    opened(effect.generation)
                }
                2 -> {
                    secondStarted.complete(Unit)
                    releaseSecond.await()
                    secondReturning.complete(Unit)
                    opened(effect.generation)
                }
                else -> null
            }
        }
    }

    private class FiniteLoopingExecutor(
        private val immediateResults: Int,
        private val terminalPublications: Int,
    ) : MeshEffectExecutor {
        val settled = CompletableDeferred<Unit>()
        private var processedPublications = 0

        override suspend fun execute(effect: MeshEffect): MeshEvent? =
            when (effect) {
                is MeshEffect.PublishPublicPayload -> {
                    processedPublications += 1
                    if (processedPublications == terminalPublications) settled.complete(Unit)
                    if (processedPublications <= immediateResults) {
                        opened(effect.generation)
                    } else {
                        null
                    }
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

    private class FenceRaceEngine : Engine<MeshState, MeshEvent, MeshEffect> {
        val blockingReductionEntered = CompletableDeferred<Unit>()
        val releaseBlockingReduction = CompletableDeferred<Unit>()
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    reducedInputs += 1
                    when (reducedInputs) {
                        1 -> Transition(
                            state = state.copy(observedAt = event.observedAt),
                            effects = List(5) { publication(event.generation) },
                        )
                        2 -> {
                            blockingReductionEntered.complete(Unit)
                            runBlocking { releaseBlockingReduction.await() }
                            Transition(state = state.copy(observedAt = event.observedAt))
                        }
                        else -> Transition(
                            state = state.copy(observedAt = event.observedAt),
                            effects = listOf(publication(event.generation)),
                        )
                    }
                }
            }
    }

    private class SettlementWatermarkEngine : Engine<MeshState, MeshEvent, MeshEffect> {
        val firstBlockEntered = CompletableDeferred<Unit>()
        val releaseFirstBlock = CompletableDeferred<Unit>()
        val secondBlockEntered = CompletableDeferred<Unit>()
        val releaseSecondBlock = CompletableDeferred<Unit>()
        val secondResultReductionEntered = CompletableDeferred<Unit>()
        val releaseSecondResultReduction = CompletableDeferred<Unit>()
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    reducedInputs += 1
                    when (reducedInputs) {
                        1,
                        3,
                        -> Transition(
                            state = state.copy(observedAt = event.observedAt),
                            effects = listOf(publication(event.generation)),
                        )
                        2 -> {
                            firstBlockEntered.complete(Unit)
                            runBlocking { releaseFirstBlock.await() }
                            Transition(state = state.copy(observedAt = event.observedAt))
                        }
                        4 -> {
                            secondBlockEntered.complete(Unit)
                            runBlocking { releaseSecondBlock.await() }
                            Transition(state = state.copy(observedAt = event.observedAt))
                        }
                        5 -> {
                            secondResultReductionEntered.complete(Unit)
                            runBlocking { releaseSecondResultReduction.await() }
                            Transition(state = state.copy(observedAt = event.observedAt))
                        }
                        else -> Transition(state = state.copy(observedAt = event.observedAt))
                    }
                }
            }
    }

    private class ResultBurstEngine(
        private val initialEffects: Int,
        private val resultEffects: Int,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    reducedInputs += 1
                    Transition(
                        state = state.copy(observedAt = event.observedAt),
                        effects = List(
                            if (reducedInputs == 1) initialEffects else resultEffects,
                        ) { publication(event.generation) },
                    )
                }
            }
    }

    private class OverflowingResultEngine(
        private val initialEffects: Int,
        private val resultEffects: Int,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        val resultReduced = CompletableDeferred<Unit>()
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    reducedInputs += 1
                    val isResult = reducedInputs > 1
                    if (isResult) resultReduced.complete(Unit)
                    Transition(
                        state = if (isResult) {
                            state.copy(lifecycle = MeshLifecycle.STOPPED)
                        } else {
                            state
                        },
                        effects = List(if (isResult) resultEffects else initialEffects) {
                            publication(event.generation)
                        },
                    )
                }
            }
    }

    private class CancellingReducerEngine : Engine<MeshState, MeshEvent, MeshEffect> {
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    reducedInputs += 1
                    if (reducedInputs > 1) throw CancellationException("reducer cancellation")
                    Transition(
                        state = state,
                        effects = listOf(publication(event.generation)),
                    )
                }
            }
    }

    private class HeldReducerEngine(
        private val release: CompletableDeferred<Unit>,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        val entered = CompletableDeferred<Unit>()
        private val delegate = MeshEngine()

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.LinkObserved -> {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                    Transition(state = state.copy(lifecycle = MeshLifecycle.RUNNING))
                }
                else -> delegate.reduce(state, event)
            }
    }

    private class CallerCancelledTeardownEngine : Engine<MeshState, MeshEvent, MeshEffect> {
        val heldReductionEntered = CompletableDeferred<Unit>()
        val releaseHeldReduction = CompletableDeferred<Unit>()
        private val delegate = MeshEngine()
        private var linkEvents = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> {
            if (event !is MeshEvent.LinkObserved) return delegate.reduce(state, event)
            linkEvents += 1
            if (linkEvents == 1) return delegate.reduce(state, event)

            heldReductionEntered.complete(Unit)
            runBlocking { releaseHeldReduction.await() }
            return Transition(
                state = state.copy(
                    lifecycle = MeshLifecycle.RUNNING,
                    observedAt = event.observedAt,
                ),
            )
        }
    }

    private class OneShotStartupThrowableEngine(
        private val failure: Throwable,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        private val delegate = MeshEngine()
        private var failed = false

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> {
            if (event is MeshEvent.RuntimeStarted && !failed) {
                failed = true
                throw failure
            }
            return delegate.reduce(state, event)
        }
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

    private class CountingBurstEngine(
        private val effectCount: Int,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        val thirdReduced = CompletableDeferred<Unit>()
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    reducedInputs += 1
                    if (reducedInputs == 3) thirdReduced.complete(Unit)
                    Transition(
                        state = state,
                        effects = List(effectCount) { publication(event.generation) },
                    )
                }
            }
    }

    private class SequencedBurstEngine(
        private val effectCounts: List<Int>,
    ) : Engine<MeshState, MeshEvent, MeshEffect> {
        private val delegate = MeshEngine()
        private var reducedInputs = 0

        override fun reduce(
            state: MeshState,
            event: MeshEvent,
        ): Transition<MeshState, MeshEffect> =
            when (event) {
                is MeshEvent.RuntimeStarted,
                is MeshEvent.RuntimeStopping,
                -> delegate.reduce(state, event)
                else -> {
                    val effectCount = effectCounts[reducedInputs]
                    reducedInputs += 1
                    Transition(
                        state = state,
                        effects = List(effectCount) { publication(event.generation) },
                    )
                }
            }
    }

    private companion object {
        fun assertStoppedAndTransientStateIsEmpty(state: MeshState) {
            assertEquals(MeshLifecycle.STOPPED, state.lifecycle)
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
