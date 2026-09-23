package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEngine
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshFixtures
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshResult
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MeshTimerDriverTest {
    @Test
    fun scheduleRegistersExactRequestWithoutWaitingForDeadline() = runTest {
        val timers = RecordingTimerDriverFactory()
        val digestStarted = CompletableDeferred<MeshEffect.ComputePacketDigest>()
        val releaseDigest = CompletableDeferred<Unit>()
        val runtime = MeshRuntime(
            MeshEngine(),
            ProtocolExecutor(digestStarted, releaseDigest),
            backgroundScope,
            MeshLimits(),
            timers,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        val generation = runtime.generation

        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(opened(generation)))
        assertEquals(SubmitResult.Accepted, runtime.submitAndAwait(decoded(generation)))
        val digest = digestStarted.await()

        val request = timers.singleDriver().requests.single()
        assertEquals(
            MeshTimerKey(digest.correlationId, TimerId.of("${digest.correlationId.value}:pending")),
            request.key,
        )
        assertEquals(generation, request.generation)
        assertEquals(MeshFixtures.now, request.observedAt)
        assertEquals(MeshFixtures.now.plus(15.seconds), request.deadline)
        assertEquals(1, assertNotNull(runtime.state.value).pendingAdmissions.size)

        releaseDigest.complete(Unit)
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(16))
        runtime.close(MeshFixtures.now)
    }

    @Test
    fun firingAndCancellationRemainExplicitAndGenerationCorrelated() = runTest {
        val timers = RecordingTimerDriverFactory()
        val runtime = MeshRuntime(
            MeshEngine(),
            ProtocolExecutor(),
            backgroundScope,
            MeshLimits(),
            timers,
        )
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        val generation = runtime.generation

        runtime.submitAndAwait(opened(generation))
        runtime.submitAndAwait(decoded(generation))
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(16))

        val state = assertNotNull(runtime.state.value)
        val dedupTimer = assertNotNull(state.dedupExpiryTimer)
        val driver = timers.singleDriver()
        val pendingRequest = driver.requests.single { it.key.timerId.value.endsWith(":pending") }
        val dedupRequest = driver.requests.single { it.key.timerId == dedupTimer.timerId }
        assertEquals(listOf(pendingRequest.key), driver.cancelledKeys)
        assertEquals(MeshTimerKey(dedupTimer.correlationId, dedupTimer.timerId), dedupRequest.key)
        assertEquals(generation, dedupRequest.generation)
        assertEquals(MeshFixtures.now, dedupRequest.observedAt)
        assertEquals(MeshFixtures.now.plus(5.minutes), dedupRequest.deadline)
        assertEquals(1, state.admittedPackets.size)

        dedupRequest.onElapsed()
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(16))
        assertTrue(assertNotNull(runtime.state.value).admittedPackets.isEmpty())
        runtime.close(MeshFixtures.now.plus(5.minutes))
    }

    @Test
    fun stopCancelsEveryRegisteredTimerAndDefaultDriverStillUsesCoroutineDelay() = runTest {
        val timers = RecordingTimerDriverFactory()
        val recordedDigestStarted = CompletableDeferred<MeshEffect.ComputePacketDigest>()
        val recordedRuntime = MeshRuntime(
            MeshEngine(),
            ProtocolExecutor(recordedDigestStarted, CompletableDeferred()),
            backgroundScope,
            MeshLimits(),
            timers,
        )
        recordedRuntime.start(MeshFixtures.localPeer, MeshFixtures.now)
        recordedRuntime.submitAndAwait(opened(recordedRuntime.generation))
        recordedRuntime.submitAndAwait(decoded(recordedRuntime.generation))
        recordedDigestStarted.await()
        assertEquals(1, timers.singleDriver().requests.size)

        assertEquals(StopResult.Stopped, recordedRuntime.stop(MeshFixtures.now))
        assertEquals(1, timers.singleDriver().cancelAllCalls)
        recordedRuntime.close(MeshFixtures.now)

        val defaultDigestStarted = CompletableDeferred<MeshEffect.ComputePacketDigest>()
        val defaultRuntime = MeshRuntime(
            MeshEngine(),
            ProtocolExecutor(defaultDigestStarted, CompletableDeferred()),
            backgroundScope,
            MeshLimits(),
        )
        defaultRuntime.start(MeshFixtures.localPeer, MeshFixtures.now)
        defaultRuntime.submitAndAwait(opened(defaultRuntime.generation))
        defaultRuntime.submitAndAwait(decoded(defaultRuntime.generation))
        defaultDigestStarted.await()
        runCurrent()
        assertEquals(1, assertNotNull(defaultRuntime.state.value).pendingAdmissions.size)

        advanceTimeBy(15.seconds)
        runCurrent()
        assertTrue(assertNotNull(defaultRuntime.state.value).pendingAdmissions.isEmpty())
        defaultRuntime.close(MeshFixtures.now.plus(15.seconds))
    }

    @Test
    fun oldJobCleanupCannotRemoveItsReplacement() = runTest {
        val driver = CoroutineMeshTimerDriver.Factory.create(backgroundScope)
        val key = MeshTimerKey(CorrelationId.of("timer-replacement"), TimerId.of("same-key"))
        val elapsed = mutableListOf<String>()
        driver.schedule(
            request(
                key = key,
                deadlineSeconds = 10,
                onElapsed = { elapsed += "old" },
            ),
        )
        driver.schedule(
            request(
                key = key,
                deadlineSeconds = 20,
                onElapsed = { elapsed += "replacement" },
            ),
        )

        runCurrent()
        advanceTimeBy(10.seconds)
        runCurrent()
        assertTrue(elapsed.isEmpty())
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf("replacement"), elapsed)
        driver.cancel(key)
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(listOf("replacement"), elapsed)
        driver.cancelAll()
    }

    @Test
    fun cancelAllClosesDriverAgainstLaterRegistrations() = runTest {
        val driver = CoroutineMeshTimerDriver.Factory.create(backgroundScope)
        val elapsed = mutableListOf<String>()
        driver.cancelAll()

        driver.schedule(
            request(
                key = MeshTimerKey(CorrelationId.of("after-close"), TimerId.of("after-close")),
                deadlineSeconds = 0,
                onElapsed = { elapsed += "elapsed" },
            ),
        )
        runCurrent()

        assertTrue(elapsed.isEmpty())
    }

    @Test
    fun restartCreatesGenerationLocalDriverAndLateOldCallbackIsSafe() = runTest {
        val timers = RecordingTimerDriverFactory()
        val runtime = MeshRuntime(MeshEngine(), ProtocolExecutor(), backgroundScope, MeshLimits(), timers)
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now)
        val oldGeneration = runtime.generation
        runtime.submitAndAwait(opened(oldGeneration))
        runtime.submitAndAwait(decoded(oldGeneration))
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(16))
        val oldDriver = timers.singleDriver()
        val oldDedupTimer = assertNotNull(assertNotNull(runtime.state.value).dedupExpiryTimer)
        val oldDedupRequest = oldDriver.requests.single { it.key.timerId == oldDedupTimer.timerId }

        runtime.stop(MeshFixtures.now.plus(1.minutes))
        runtime.start(MeshFixtures.localPeer, MeshFixtures.now.plus(2.minutes))
        val newDriver = timers.drivers.last()
        assertNotSame(oldDriver, newDriver)
        assertEquals(oldGeneration.next(), runtime.generation)
        assertEquals(1, oldDriver.cancelAllCalls)
        assertEquals(0, newDriver.cancelAllCalls)
        assertEquals(runtime.generation, newDriver.requests.single().generation)
        val beforeLateCallback = runtime.state.value

        oldDedupRequest.onElapsed()
        assertIs<RuntimeQuiescenceResult.Quiescent>(runtime.awaitImmediateQuiescence(16))
        assertEquals(beforeLateCallback, runtime.state.value)
        runtime.close(MeshFixtures.now.plus(2.minutes))
    }

    @Test
    fun factoryFailureCannotLeakRunSupervisor() = runTest {
        val createdJobs = mutableListOf<Job>()
        var creationCount = 0
        val factoryFailure = MeshTimerDriverFactory { scope ->
            createdJobs += assertNotNull(scope.coroutineContext[Job])
            if (creationCount++ == 0) error("factory failure")
            RecordingTimerDriver()
        }
        val factoryRuntime = MeshRuntime(
            MeshEngine(),
            ProtocolExecutor(),
            backgroundScope,
            MeshLimits(),
            factoryFailure,
        )

        assertFailsWith<IllegalStateException> {
            factoryRuntime.start(MeshFixtures.localPeer, MeshFixtures.now)
        }
        assertFalse(createdJobs.single().isActive)
        assertEquals(StartResult.Started, factoryRuntime.start(MeshFixtures.localPeer, MeshFixtures.now))
        factoryRuntime.close(MeshFixtures.now)
    }

    @Test
    fun cancelAllFailureCannotLeakRunSupervisor() = runTest {
        val stopJobs = mutableListOf<Job>()
        var stopCreationCount = 0
        val cancelFailure = MeshTimerDriverFactory { scope ->
            stopJobs += assertNotNull(scope.coroutineContext[Job])
            if (stopCreationCount++ == 0) {
                object : RecordingTimerDriver() {
                    override suspend fun cancelAll() {
                        super.cancelAll()
                        error("cancelAll failure")
                    }
                }
            } else {
                RecordingTimerDriver()
            }
        }
        val stopRuntime = MeshRuntime(
            MeshEngine(),
            ProtocolExecutor(),
            backgroundScope,
            MeshLimits(),
            cancelFailure,
        )
        stopRuntime.start(MeshFixtures.localPeer, MeshFixtures.now)

        assertFailsWith<IllegalStateException> { stopRuntime.stop(MeshFixtures.now) }
        assertFalse(stopJobs.first().isActive)
        assertEquals(StartResult.Started, stopRuntime.start(MeshFixtures.localPeer, MeshFixtures.now))
        stopRuntime.close(MeshFixtures.now)
    }

    private open class RecordingTimerDriver : MeshTimerDriver {
        val requests = mutableListOf<MeshTimerRequest>()
        val cancelledKeys = mutableListOf<MeshTimerKey>()
        var cancelAllCalls: Int = 0

        override suspend fun schedule(request: MeshTimerRequest) {
            requests += request
        }

        override suspend fun cancel(key: MeshTimerKey) {
            cancelledKeys += key
        }

        override suspend fun cancelAll() {
            cancelAllCalls += 1
        }
    }

    private class RecordingTimerDriverFactory : MeshTimerDriverFactory {
        val drivers = mutableListOf<RecordingTimerDriver>()

        override fun create(scope: CoroutineScope): MeshTimerDriver =
            RecordingTimerDriver().also(drivers::add)

        fun singleDriver(): RecordingTimerDriver = drivers.single()
    }

    private class ProtocolExecutor(
        private val digestStarted: CompletableDeferred<MeshEffect.ComputePacketDigest>? = null,
        private val releaseDigest: CompletableDeferred<Unit>? = null,
    ) : MeshEffectExecutor {
        override suspend fun execute(effect: MeshEffect): MeshEvent? =
            when (effect) {
                is MeshEffect.ComputePacketDigest -> {
                    digestStarted?.complete(effect)
                    releaseDigest?.await()
                    MeshEvent.PacketDigestComputed(
                        correlationId = effect.correlationId,
                        generation = effect.generation,
                        observedAt = MeshFixtures.now,
                        result = MeshResult.Success(MeshFixtures.fakeSha256Digest),
                    )
                }
                is MeshEffect.PublishPublicPayload -> null
                else -> error("Unexpected executor effect: ${effect::class.simpleName}")
            }
    }

    private companion object {
        fun opened(generation: Generation): MeshEvent.LinkObserved = MeshEvent.LinkObserved(
            generation = generation,
            observedAt = MeshFixtures.now,
            event = LinkEvent.Opened(
                MeshFixtures.linkA,
                LinkCapabilities(maxWriteBytes = 4096, writeReady = true),
            ),
        )

        fun decoded(generation: Generation): MeshEvent.PacketDecoded = MeshFixtures.packetDecoded(
            packet = MeshFixtures.broadcastPacket.copy(ttl = 0u),
            eventGeneration = generation,
            observedAt = MeshFixtures.now,
        )

        fun request(
            key: MeshTimerKey,
            deadlineSeconds: Int,
            onElapsed: suspend () -> Unit,
        ): MeshTimerRequest = MeshTimerRequest(
            key = key,
            generation = Generation(0),
            observedAt = MeshFixtures.now,
            deadline = MeshFixtures.now.plus(deadlineSeconds.seconds),
            onElapsed = onElapsed,
        )
    }
}
