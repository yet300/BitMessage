package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.MeshLimits
import com.yet.bitmessage.engine.mesh.MeshLifecycle
import com.yet.bitmessage.engine.mesh.MeshState
import com.yet.bitmessage.engine.mesh.PacketSource
import com.yet.bitmessage.engine.mesh.SnapshotMap
import com.yet.bitmessage.foundation.Engine
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TraceRecord
import com.yet.bitmessage.foundation.Transition
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.transport.api.LinkEvent
import kotlinx.coroutines.CancellationException
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

    data class StructuralDecodeRejected(
        val error: DecodeError,
    ) : SubmitResult
}

sealed interface RuntimeQuiescenceResult {
    data class Quiescent(
        val processedEffects: Long,
    ) : RuntimeQuiescenceResult

    data object Closed : RuntimeQuiescenceResult

    data class LimitExceeded(
        val maximumEffects: Int,
    ) : RuntimeQuiescenceResult
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
    private val mutableStructuralDecodeRejectionCount = MutableStateFlow(0L)

    private var activeRun: RunContext? = null
    private var accepting: Boolean = false
    private var stopping: Boolean = false
    private var permanentlyClosed: Boolean = false

    val state: StateFlow<MeshState?> = mutableState.asStateFlow()
    val droppedTraceCount: StateFlow<Long> = mutableDroppedTraceCount.asStateFlow()
    val structuralDecodeRejectionCount: StateFlow<Long> = mutableStructuralDecodeRejectionCount.asStateFlow()

    val generation: Generation
        get() = mutableState.value?.generation ?: Generation(0)

    val traceEvents: ReceiveChannel<TraceRecord>?
        get() {
            if (!lifecycleMutex.tryLock()) return null
            return try {
                activeRun?.takeIf { it.actorJob.isActive }?.traces
            } finally {
                lifecycleMutex.unlock()
            }
        }

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
            context.actorJob.invokeOnCompletion { failure ->
                if (failure != null && failure !is CancellationException) {
                    context.supervisor.cancel()
                }
            }
            context.effectJob.invokeOnCompletion { failure ->
                if (failure != null && failure !is CancellationException) {
                    context.supervisor.cancel()
                }
            }

            try {
                check(
                    reduceControl(
                        context,
                        MeshEvent.RuntimeStarted(nextGeneration, observedAt, localPeer),
                    ),
                ) { "Mesh runtime actor terminated during start." }
            } catch (cancelled: CancellationException) {
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
        if (!lifecycleMutex.tryLock()) return SubmitResult.Backpressured
        return try {
            if (!accepting) return SubmitResult.Closed
            val context = activeRun?.takeIf { it.actorJob.isActive } ?: return SubmitResult.Closed
            enqueue(context, event, acknowledged = null)
        } finally {
            lifecycleMutex.unlock()
        }
    }

    suspend fun submitAndAwait(event: MeshEvent): SubmitResult {
        if (!lifecycleMutex.tryLock()) return SubmitResult.Backpressured
        lateinit var context: RunContext
        lateinit var acknowledged: CompletableDeferred<Unit>
        val result = try {
            if (!accepting) return SubmitResult.Closed
            context = activeRun?.takeIf { it.actorJob.isActive } ?: return SubmitResult.Closed
            acknowledged = CompletableDeferred()
            enqueue(context, event, acknowledged)
        } finally {
            lifecycleMutex.unlock()
        }
        if (result != SubmitResult.Accepted) return result
        return awaitExternalAcknowledgement(context, acknowledged)
    }

    fun trySubmit(
        event: LinkEvent,
        observedAt: MonotonicTime,
    ): SubmitResult {
        if (!lifecycleMutex.tryLock()) return SubmitResult.Backpressured
        return try {
            if (!accepting) return SubmitResult.Closed
            val context = activeRun?.takeIf { it.actorJob.isActive } ?: return SubmitResult.Closed
            val currentGeneration = requireNotNull(mutableState.value).generation
            val meshEvent = when (event) {
                is LinkEvent.PayloadReceived -> {
                    if (event.bytes.size > limits.maxPendingPacketBytes) {
                        recordStructuralDecodeRejection()
                        return SubmitResult.StructuralDecodeRejected(DecodeError.LIMIT_EXCEEDED)
                    }
                    when (
                        val decoded = MeshProtocolAdapter.decode(
                            generation = currentGeneration,
                            observedAt = observedAt,
                            source = PacketSource.Link(event.linkId),
                            bytes = event.bytes,
                        )
                    ) {
                        is PacketIngress.Accepted -> decoded.event
                        is PacketIngress.Rejected -> {
                            recordStructuralDecodeRejection()
                            return SubmitResult.StructuralDecodeRejected(decoded.error)
                        }
                    }
                }
                else -> MeshEvent.LinkObserved(
                    generation = currentGeneration,
                    observedAt = observedAt,
                    event = event,
                )
            }
            enqueue(context, meshEvent, acknowledged = null)
        } finally {
            lifecycleMutex.unlock()
        }
    }

    suspend fun submitAndAwait(
        event: LinkEvent,
        observedAt: MonotonicTime,
    ): SubmitResult {
        if (!lifecycleMutex.tryLock()) return SubmitResult.Backpressured
        lateinit var context: RunContext
        lateinit var acknowledged: CompletableDeferred<Unit>
        val result = try {
            if (!accepting) return SubmitResult.Closed
            context = activeRun?.takeIf { it.actorJob.isActive } ?: return SubmitResult.Closed
            val currentGeneration = requireNotNull(mutableState.value).generation
            val meshEvent = when (event) {
                is LinkEvent.PayloadReceived -> {
                    if (event.bytes.size > limits.maxPendingPacketBytes) {
                        recordStructuralDecodeRejection()
                        return SubmitResult.StructuralDecodeRejected(DecodeError.LIMIT_EXCEEDED)
                    }
                    when (
                        val decoded = MeshProtocolAdapter.decode(
                            generation = currentGeneration,
                            observedAt = observedAt,
                            source = PacketSource.Link(event.linkId),
                            bytes = event.bytes,
                        )
                    ) {
                        is PacketIngress.Accepted -> decoded.event
                        is PacketIngress.Rejected -> {
                            recordStructuralDecodeRejection()
                            return SubmitResult.StructuralDecodeRejected(decoded.error)
                        }
                    }
                }
                else -> MeshEvent.LinkObserved(
                    generation = currentGeneration,
                    observedAt = observedAt,
                    event = event,
                )
            }
            acknowledged = CompletableDeferred()
            enqueue(context, meshEvent, acknowledged)
        } finally {
            lifecycleMutex.unlock()
        }
        if (result != SubmitResult.Accepted) return result
        return awaitExternalAcknowledgement(context, acknowledged)
    }

    suspend fun awaitImmediateQuiescence(
        maxProcessedEffects: Int,
    ): RuntimeQuiescenceResult {
        require(maxProcessedEffects > 0) { "Maximum processed effects must be positive." }
        val context = lifecycleMutex.withLock {
            if (!accepting) return@withLock null
            activeRun?.takeIf { it.actorJob.isActive && it.effectJob.isActive }
        } ?: return RuntimeQuiescenceResult.Closed

        return context.fenceMutex.withLock {
            val initialProcessedEffects = context.lastReportedProcessedEffects
            while (true) {
                val processedEffects = awaitEffectFence(context)
                    ?: return@withLock RuntimeQuiescenceResult.Closed
                val registeredEffects = awaitRegisteredEffectCount(context)
                    ?: return@withLock RuntimeQuiescenceResult.Closed
                check(processedEffects >= initialProcessedEffects) {
                    "Processed effect count moved behind the causal-fence cursor."
                }
                check(processedEffects <= registeredEffects) {
                    "Processed effect count exceeded registered effect count."
                }
                val processedSinceLastFence = processedEffects - initialProcessedEffects
                if (processedSinceLastFence > maxProcessedEffects.toLong()) {
                    context.lastReportedProcessedEffects = processedEffects
                    return@withLock RuntimeQuiescenceResult.LimitExceeded(maxProcessedEffects)
                }
                if (processedEffects == registeredEffects) {
                    context.lastReportedProcessedEffects = processedEffects
                    return@withLock RuntimeQuiescenceResult.Quiescent(processedSinceLastFence)
                }
            }
            error("Causal fence loop terminated unexpectedly.")
        }
    }

    private fun enqueue(
        context: RunContext,
        event: MeshEvent,
        acknowledged: CompletableDeferred<Unit>?,
    ): SubmitResult {
        val result = context.external.trySend(ExternalEnvelope(event, acknowledged))
        return when {
            result.isSuccess -> SubmitResult.Accepted
            result.isClosed -> SubmitResult.Closed
            else -> SubmitResult.Backpressured
        }
    }

    private suspend fun awaitExternalAcknowledgement(
        context: RunContext,
        acknowledged: CompletableDeferred<Unit>,
    ): SubmitResult = select {
        acknowledged.onAwait { SubmitResult.Accepted }
        context.actorJob.onJoin {
            if (acknowledged.isCompleted) {
                acknowledged.await()
                SubmitResult.Accepted
            } else {
                SubmitResult.Closed
            }
        }
    }

    suspend fun stop(observedAt: MonotonicTime): StopResult =
        lifecycleMutex.withLock { stopActiveRunLocked(observedAt) }

    suspend fun close(observedAt: MonotonicTime) {
        lifecycleMutex.withLock {
            permanentlyClosed = true
            accepting = false
            stopActiveRunLocked(observedAt)
        }
    }

    private suspend fun stopActiveRunLocked(observedAt: MonotonicTime): StopResult {
        if (stopping) return StopResult.AlreadyStopped
        val context = activeRun ?: return StopResult.AlreadyStopped
        stopping = true
        accepting = false

        try {
            val currentState = requireNotNull(mutableState.value)
            val stoppingEvent = MeshEvent.RuntimeStopping(currentState.generation, observedAt)
            if (!reduceControl(context, stoppingEvent)) {
                reduceAndPublish(context, stoppingEvent)
            }
        } finally {
            withContext(NonCancellable) {
                shutdownContext(context)
                if (activeRun === context) activeRun = null
                stopping = false
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
            controls = Channel(1),
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
        var preferActorCommand = true
        while (context.scope.isActive) {
            val input = if (preferActorCommand) {
                select<ActorInput> {
                    context.controls.onReceive { ActorInput.Command(it) }
                    context.results.onReceive { ActorInput.Result(it) }
                    context.external.onReceive { ActorInput.External(it) }
                }
            } else {
                select {
                    context.results.onReceive { ActorInput.Result(it) }
                    context.controls.onReceive { ActorInput.Command(it) }
                    context.external.onReceive { ActorInput.External(it) }
                }
            }
            preferActorCommand = when (input) {
                is ActorInput.Command -> {
                    handleActorCommand(context, input.command)
                    false
                }
                is ActorInput.Result -> {
                    reduceAndPublish(context, input.event)
                    true
                }
                is ActorInput.External -> {
                    reduceAcknowledged(context, input.envelope)
                    true
                }
            }
        }
    }

    private suspend fun handleActorCommand(
        context: RunContext,
        command: ActorCommand,
    ) {
        when (command) {
            is ActorCommand.Reduce -> reduceAcknowledged(
                context,
                ExternalEnvelope(command.event, command.acknowledged),
            )
            is ActorCommand.EffectCount -> command.acknowledged.complete(context.registeredEffects)
        }
    }

    private suspend fun reduceAcknowledged(
        context: RunContext,
        envelope: ExternalEnvelope,
    ) {
        try {
            reduceAndPublish(context, envelope.event)
        } catch (failure: Throwable) {
            envelope.acknowledged?.completeExceptionally(failure)
            throw failure
        }
        envelope.acknowledged?.complete(Unit)
    }

    private suspend fun reduceControl(
        context: RunContext,
        event: MeshEvent,
    ): Boolean {
        if (!context.actorJob.isActive) return false
        val acknowledged = CompletableDeferred<Unit>()
        if (!sendActorCommand(context, ActorCommand.Reduce(event, acknowledged))) return false
        return select {
            acknowledged.onAwait { true }
            context.actorJob.onJoin {
                if (acknowledged.isCompleted) {
                    acknowledged.await()
                    true
                } else {
                    false
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
            context.registeredEffects = checkedIncrement(
                context.registeredEffects,
                "Registered effect count",
            )
            context.effects.send(
                EffectCommand.Execute(
                    EffectEnvelope(
                        effect = effect,
                        observedAt = transition.state.observedAt,
                    ),
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
        for (command in context.effects) {
            when (command) {
                is EffectCommand.Execute -> {
                    check(context.processedEffects != Long.MAX_VALUE) {
                        "Processed effect count overflow."
                    }
                    processEffect(context, command.envelope)
                    context.processedEffects += 1
                }
                is EffectCommand.Fence -> {
                    command.acknowledged.complete(context.processedEffects)
                }
            }
        }
    }

    private suspend fun processEffect(
        context: RunContext,
        envelope: EffectEnvelope,
    ) {
        when (val effect = envelope.effect) {
            is MeshEffect.Schedule -> scheduleTimer(context, envelope, effect)
            is MeshEffect.Cancel -> cancelTimer(context, effect)
            is MeshEffect.ReinjectPacket -> {
                when (
                    val decoded = MeshProtocolAdapter.decode(
                        generation = effect.generation,
                        observedAt = envelope.observedAt,
                        source = effect.source,
                        bytes = effect.bytes,
                    )
                ) {
                    is PacketIngress.Accepted -> context.results.send(decoded.event)
                    is PacketIngress.Rejected -> recordStructuralDecodeRejection()
                }
            }
            else -> executeEffect(executor, effect, envelope.observedAt)?.let { event ->
                context.results.send(event)
            }
        }
    }

    private suspend fun awaitEffectFence(context: RunContext): Long? {
        if (!context.effectJob.isActive) return null
        val acknowledged = CompletableDeferred<Long>()
        val sent = select {
            context.effectJob.onJoin { false }
            context.effects.onSend(EffectCommand.Fence(acknowledged)) { true }
        }
        if (!sent) return null
        return select {
            acknowledged.onAwait { it }
            context.effectJob.onJoin {
                if (acknowledged.isCompleted) acknowledged.await() else null
            }
        }
    }

    private suspend fun awaitRegisteredEffectCount(context: RunContext): Long? {
        if (!context.actorJob.isActive) return null
        val acknowledged = CompletableDeferred<Long>()
        if (!sendActorCommand(context, ActorCommand.EffectCount(acknowledged))) return null
        return select {
            acknowledged.onAwait { it }
            context.actorJob.onJoin {
                if (acknowledged.isCompleted) acknowledged.await() else null
            }
        }
    }

    private suspend fun sendActorCommand(
        context: RunContext,
        command: ActorCommand,
    ): Boolean {
        if (!context.actorJob.isActive) return false
        return select {
            context.actorJob.onJoin { false }
            context.controls.onSend(command) { true }
        }
    }

    private fun recordStructuralDecodeRejection() {
        mutableStructuralDecodeRejectionCount.update { current ->
            if (current == Long.MAX_VALUE) current else current + 1
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
        val external: Channel<ExternalEnvelope>,
        val effects: Channel<EffectCommand>,
        val results: Channel<MeshEvent>,
        val controls: Channel<ActorCommand>,
        val traces: Channel<TraceRecord>,
    ) {
        val timerMutex = Mutex()
        val timers = mutableMapOf<TimerKey, Job>()
        val fenceMutex = Mutex()
        var registeredEffects: Long = 0
        var processedEffects: Long = 0
        var lastReportedProcessedEffects: Long = 0
        lateinit var actorJob: Job
        lateinit var effectJob: Job
    }

    private sealed interface ActorCommand {
        data class Reduce(
            val event: MeshEvent,
            val acknowledged: CompletableDeferred<Unit>,
        ) : ActorCommand

        data class EffectCount(
            val acknowledged: CompletableDeferred<Long>,
        ) : ActorCommand
    }

    private sealed interface ActorInput {
        data class Command(
            val command: ActorCommand,
        ) : ActorInput

        data class Result(
            val event: MeshEvent,
        ) : ActorInput

        data class External(
            val envelope: ExternalEnvelope,
        ) : ActorInput
    }

    private sealed interface EffectCommand {
        data class Execute(
            val envelope: EffectEnvelope,
        ) : EffectCommand

        data class Fence(
            val acknowledged: CompletableDeferred<Long>,
        ) : EffectCommand
    }

    private data class ExternalEnvelope(
        val event: MeshEvent,
        val acknowledged: CompletableDeferred<Unit>?,
    )

    private data class EffectEnvelope(
        val effect: MeshEffect,
        val observedAt: MonotonicTime,
    )

    private data class TimerKey(
        val correlationValue: String,
        val timerId: TimerId,
    )

    private companion object {
        fun checkedIncrement(
            value: Long,
            label: String,
        ): Long {
            check(value != Long.MAX_VALUE) { "$label overflow." }
            return value + 1
        }
    }
}
