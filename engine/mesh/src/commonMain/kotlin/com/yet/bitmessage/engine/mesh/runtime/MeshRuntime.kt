package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshLifecycle
import com.yet.bitmessage.engine.mesh.MeshState
import com.yet.bitmessage.engine.mesh.SnapshotMap
import com.yet.bitmessage.foundation.Engine
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TraceRecord
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SubmitResult {
    data object Accepted : SubmitResult

    data object Backpressured : SubmitResult

    data object Closed : SubmitResult
}

sealed interface StartResult {
    data object Started : StartResult

    data object AlreadyStarted : StartResult

    data object Closed : StartResult
}

sealed interface StopResult {
    data object Stopped : StopResult

    data object AlreadyStopped : StopResult
}

class MeshRuntime(
    private val engine: Engine<MeshState, MeshEvent, MeshEffect>,
    private val executor: MeshEffectExecutor,
    private val parentScope: CoroutineScope,
    private val limits: MeshLimits,
) {
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow<MeshState?>(null)
    private val mutableDroppedTraceCount = MutableStateFlow(0L)

    private var activeRun: RunContext? = null
    private var accepting: Boolean = false
    private var stopping: Boolean = false
    private var permanentlyClosed: Boolean = false

    val state: StateFlow<MeshState?> = mutableState.asStateFlow()
    val droppedTraceCount: StateFlow<Long> = mutableDroppedTraceCount.asStateFlow()

    val generation: Generation
        get() = mutableState.value?.generation ?: Generation(0)

    val traceEvents: ReceiveChannel<TraceRecord>?
        get() = activeRun?.traces

    suspend fun start(
        localPeer: WirePeerId,
        observedAt: MonotonicTime,
    ): StartResult =
        lifecycleMutex.withLock {
            if (permanentlyClosed) return@withLock StartResult.Closed
            if (activeRun != null || stopping) return@withLock StartResult.AlreadyStarted

            val previous = mutableState.value
            val nextGeneration = previous?.generation?.next() ?: Generation(0)
            val retainedAdmitted = previous?.admittedPackets
                ?.filterValues { it.expiresAt > observedAt }
                .orEmpty()
            mutableState.value = MeshState(
                generation = nextGeneration,
                localPeer = localPeer,
                observedAt = observedAt,
                lifecycle = MeshLifecycle.STOPPED,
                admittedPackets = SnapshotMap(retainedAdmitted),
            )

            val context = createRunContext()
            activeRun = context
            context.actorJob = context.scope.launch { actorLoop(context) }
            context.effectJob = context.scope.launch { effectLoop(context) }

            val acknowledged = CompletableDeferred<Unit>()
            try {
                context.controls.send(
                    ActorCommand.Reduce(
                        event = MeshEvent.RuntimeStarted(nextGeneration, observedAt, localPeer),
                        acknowledged = acknowledged,
                    ),
                )
                acknowledged.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) { shutdownContext(context) }
                activeRun = null
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) { shutdownContext(context) }
                activeRun = null
                throw failure
            }
            accepting = true
            StartResult.Started
        }

    fun trySubmit(event: MeshEvent): SubmitResult {
        if (!accepting) return SubmitResult.Closed
        val mailbox = activeRun?.external ?: return SubmitResult.Closed
        val result = mailbox.trySend(event)
        return when {
            result.isSuccess -> SubmitResult.Accepted
            result.isClosed -> SubmitResult.Closed
            else -> SubmitResult.Backpressured
        }
    }

    suspend fun stop(observedAt: MonotonicTime): StopResult = stopActiveRun(observedAt)

    suspend fun close(observedAt: MonotonicTime) {
        val shouldClose = lifecycleMutex.withLock {
            if (permanentlyClosed) {
                false
            } else {
                permanentlyClosed = true
                accepting = false
                true
            }
        }
        if (shouldClose) stopActiveRun(observedAt)
    }

    private suspend fun stopActiveRun(observedAt: MonotonicTime): StopResult {
        val context = lifecycleMutex.withLock {
            if (stopping) return@withLock null
            val current = activeRun ?: return@withLock null
            stopping = true
            accepting = false
            current
        } ?: return StopResult.AlreadyStopped

        try {
            val currentState = requireNotNull(mutableState.value)
            val acknowledged = CompletableDeferred<Unit>()
            context.controls.send(
                ActorCommand.Reduce(
                    event = MeshEvent.RuntimeStopping(currentState.generation, observedAt),
                    acknowledged = acknowledged,
                ),
            )
            acknowledged.await()
        } finally {
            withContext(NonCancellable) {
                shutdownContext(context)
                lifecycleMutex.withLock {
                    if (activeRun === context) activeRun = null
                    stopping = false
                }
            }
        }
        return StopResult.Stopped
    }

    private fun createRunContext(): RunContext {
        val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
        val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
        return RunContext(
            supervisor = supervisor,
            scope = scope,
            external = Channel(limits.eventMailboxCapacity),
            effects = Channel(limits.effectQueueCapacity),
            results = Channel(Channel.RENDEZVOUS),
            controls = Channel(Channel.RENDEZVOUS),
            traces = Channel(limits.traceBufferCapacity),
        )
    }

    private suspend fun shutdownContext(context: RunContext) {
        val timerJobs = context.timerMutex.withLock {
            context.timers.values.toList().also { context.timers.clear() }
        }
        timerJobs.forEach(Job::cancel)
        context.supervisor.cancelAndJoin()
        context.external.close()
        context.effects.close()
        context.results.close()
        context.controls.close()
        context.traces.close()
    }

    private suspend fun actorLoop(context: RunContext) {
        while (context.scope.isActive) {
            select<Unit> {
                context.results.onReceive { event ->
                    reduceAndPublish(context, event)
                }
                context.controls.onReceive { command ->
                    reduceAndPublish(context, command.event)
                    command.acknowledged.complete(Unit)
                }
                context.external.onReceive { event ->
                    reduceAndPublish(context, event)
                }
            }
        }
    }

    private suspend fun reduceAndPublish(
        context: RunContext,
        event: MeshEvent,
    ) {
        val current = requireNotNull(mutableState.value)
        val transition = engine.reduce(current, event)
        mutableState.value = transition.state
        publishTrace(context, transition)
        transition.effects.forEach { effect ->
            context.effects.send(
                EffectEnvelope(
                    effect = effect,
                    observedAt = transition.state.observedAt,
                ),
            )
        }
    }

    private fun publishTrace(
        context: RunContext,
        transition: Transition<MeshState, MeshEffect>,
    ) {
        transition.trace.forEach { trace ->
            if (context.traces.trySend(trace).isFailure) {
                mutableDroppedTraceCount.update { current ->
                    if (current == Long.MAX_VALUE) current else current + 1
                }
            }
        }
    }

    private suspend fun effectLoop(context: RunContext) {
        for (envelope in context.effects) {
            when (val effect = envelope.effect) {
                is MeshEffect.Schedule -> scheduleTimer(context, envelope, effect)
                is MeshEffect.Cancel -> cancelTimer(context, effect)
                else -> executeEffect(executor, effect, envelope.observedAt)?.let { event ->
                    context.results.send(event)
                }
            }
        }
    }

    private suspend fun scheduleTimer(
        context: RunContext,
        envelope: EffectEnvelope,
        effect: MeshEffect.Schedule,
    ) {
        val key = TimerKey(effect.correlationId.value, effect.timerId)
        lateinit var timerJob: Job
        timerJob = context.scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(effect.delay)
                context.results.send(
                    MeshEvent.TimerElapsed(
                        correlationId = effect.correlationId,
                        generation = effect.generation,
                        observedAt = envelope.observedAt.plus(effect.delay),
                        timerId = effect.timerId,
                    ),
                )
            } finally {
                withContext(NonCancellable) {
                    context.timerMutex.withLock {
                        if (context.timers[key] === timerJob) context.timers.remove(key)
                    }
                }
            }
        }
        val previous = context.timerMutex.withLock { context.timers.put(key, timerJob) }
        previous?.cancel()
        timerJob.start()
    }

    private suspend fun cancelTimer(
        context: RunContext,
        effect: MeshEffect.Cancel,
    ) {
        val key = TimerKey(effect.correlationId.value, effect.timerId)
        context.timerMutex.withLock { context.timers.remove(key) }?.cancel()
    }

    private class RunContext(
        val supervisor: Job,
        val scope: CoroutineScope,
        val external: Channel<MeshEvent>,
        val effects: Channel<EffectEnvelope>,
        val results: Channel<MeshEvent>,
        val controls: Channel<ActorCommand>,
        val traces: Channel<TraceRecord>,
    ) {
        val timerMutex = Mutex()
        val timers = mutableMapOf<TimerKey, Job>()
        lateinit var actorJob: Job
        lateinit var effectJob: Job
    }

    private sealed interface ActorCommand {
        val event: MeshEvent
        val acknowledged: CompletableDeferred<Unit>

        data class Reduce(
            override val event: MeshEvent,
            override val acknowledged: CompletableDeferred<Unit>,
        ) : ActorCommand
    }

    private data class EffectEnvelope(
        val effect: MeshEffect,
        val observedAt: MonotonicTime,
    )

    private data class TimerKey(
        val correlationValue: String,
        val timerId: TimerId,
    )
}
