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
                if (failure != null) {
                    context.supervisor.cancel()
                }
            }
            context.effectJob.invokeOnCompletion { failure ->
                if (failure != null) {
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
                cleanupFailedStart(context)
                throw cancelled
            } catch (failure: Throwable) {
                cleanupFailedStart(context)
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

    /**
     * Waits until [event]'s state is committed, trace publication has been attempted under the configured
     * drop policy, the registered-effect count is advanced, and its complete effect list is admitted to the
     * actor's bounded ordered ledger. Asynchronous effect execution is not part of this acknowledgement.
     */
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
            when (val ingress = adaptLinkIngressLocked(event, observedAt)) {
                is LinkIngress.Accepted -> enqueue(context, ingress.event, acknowledged = null)
                is LinkIngress.Rejected -> ingress.result
            }
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
            when (val ingress = adaptLinkIngressLocked(event, observedAt)) {
                is LinkIngress.Accepted -> {
                    acknowledged = CompletableDeferred()
                    enqueue(context, ingress.event, acknowledged)
                }
                is LinkIngress.Rejected -> ingress.result
            }
        } finally {
            lifecycleMutex.unlock()
        }
        if (result != SubmitResult.Accepted) return result
        return awaitExternalAcknowledgement(context, acknowledged)
    }

    /**
     * Fences effects caused by submissions whose [submitAndAwait] acknowledgement completed before this
     * call. Callers must exclude concurrent [trySubmit] calls and other unacknowledged external ingress;
     * events still waiting in the external mailbox are outside this causal ordering guarantee.
     */
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

    private fun adaptLinkIngressLocked(
        event: LinkEvent,
        observedAt: MonotonicTime,
    ): LinkIngress {
        val currentGeneration = requireNotNull(mutableState.value).generation
        if (event !is LinkEvent.PayloadReceived) {
            return LinkIngress.Accepted(
                MeshEvent.LinkObserved(
                    generation = currentGeneration,
                    observedAt = observedAt,
                    event = event,
                ),
            )
        }
        if (event.bytes.size > limits.maxPendingPacketBytes) {
            recordStructuralDecodeRejection()
            return LinkIngress.Rejected(
                SubmitResult.StructuralDecodeRejected(DecodeError.LIMIT_EXCEEDED),
            )
        }
        return when (
            val decoded = MeshProtocolAdapter.decode(
                generation = currentGeneration,
                observedAt = observedAt,
                source = PacketSource.Link(event.linkId),
                bytes = event.bytes,
            )
        ) {
            is PacketIngress.Accepted -> LinkIngress.Accepted(decoded.event)
            is PacketIngress.Rejected -> {
                recordStructuralDecodeRejection()
                LinkIngress.Rejected(SubmitResult.StructuralDecodeRejected(decoded.error))
            }
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
    ): SubmitResult =
        try {
            select {
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
        } catch (cancelled: CancellationException) {
            acknowledged.cancel(cancelled)
            throw cancelled
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
        val currentState = requireNotNull(mutableState.value)
        val stoppingEvent = MeshEvent.RuntimeStopping(currentState.generation, observedAt)
        var reduceAfterTermination = false
        var callerCancellation: CancellationException? = null

        try {
            try {
                if (!reduceControl(context, stoppingEvent)) {
                    context.actorJob.join()
                    reduceAfterTermination = true
                }
            } catch (cancelled: CancellationException) {
                reduceAfterTermination = true
                callerCancellation = cancelled
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    terminateContextJobs(context)
                    if (
                        reduceAfterTermination &&
                        activeRun === context &&
                        mutableState.value?.generation == stoppingEvent.generation &&
                        mutableState.value?.lifecycle != MeshLifecycle.STOPPED
                    ) {
                        reduceStoppingAfterActorTermination(context, stoppingEvent)
                    }
                } finally {
                    closeContextChannels(context)
                    if (activeRun === context) activeRun = null
                    stopping = false
                }
            }
        }
        callerCancellation?.let { throw it }
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
            effectSettlements = Channel(Channel.RENDEZVOUS),
            lifecycleControls = Channel(Channel.RENDEZVOUS),
            controls = Channel(1),
            traces = Channel(limits.traceBufferCapacity),
        )
    }

    private suspend fun cleanupFailedStart(context: RunContext) {
        withContext(NonCancellable) {
            try {
                shutdownContext(context)
            } finally {
                if (activeRun === context) activeRun = null
            }
        }
    }

    private suspend fun shutdownContext(context: RunContext) {
        terminateContextJobs(context)
        closeContextChannels(context)
    }

    private suspend fun terminateContextJobs(context: RunContext) {
        val timerJobs = context.timerMutex.withLock {
            context.timers.values.toList().also { context.timers.clear() }
        }
        timerJobs.forEach(Job::cancel)
        context.supervisor.cancelAndJoin()
    }

    private fun closeContextChannels(context: RunContext) {
        context.external.close()
        context.effects.close()
        context.results.close()
        context.effectSettlements.close()
        context.lifecycleControls.close()
        context.controls.close()
        context.traces.close()
    }

    private suspend fun actorLoop(context: RunContext) {
        val pendingEffects = ArrayDeque<EffectCommand.Execute>()
        // Each path is FIFO. Settlements have causal priority; independent async results are staged only
        // when enough logical credit remains for one maximum-size transition.
        val settledEffectResults = ArrayDeque<MeshEvent>()
        val asynchronousResults = ArrayDeque<MeshEvent>()
        val effectCapacity = limits.effectQueueCapacity
        val maximumCausalWork = checkedCausalWorkCapacity(effectCapacity)
        var causalWork = 0L
        var inFlightEffects = 0L
        var stagedTransition: StagedTransition? = null
        var pendingLifecycleControl: ActorCommand.Reduce? = null
        var pendingControl: ActorCommand? = null
        var pendingFence: EffectCommand.Fence? = null

        while (context.scope.isActive) {
            val stoppingCommand = pendingLifecycleControl
                ?.takeIf { it.event is MeshEvent.RuntimeStopping }
            if (stoppingCommand != null) {
                pendingLifecycleControl = null
                val stopping = stageTransition(
                    StagedEvent(
                        event = stoppingCommand.event,
                        acknowledged = stoppingCommand.acknowledged,
                        replacesCausalCredit = false,
                    ),
                    effectCapacity,
                )
                // Teardown preempts uncommitted work, commits STOPPED through the sole writer, and exits
                // before any staged result can publish a late state over it. Teardown effects are not
                // registered: shutdown cancels the effect worker immediately after this acknowledgement.
                mutableState.value = stopping.transition.state
                publishTrace(context, stopping.transition)
                stopping.acknowledged?.complete(Unit)
                return
            }
            if (pendingFence?.acknowledged?.isCancelled == true) pendingFence = null
            if (
                (pendingControl as? ActorCommand.EffectCount)?.acknowledged?.isCancelled == true ||
                (pendingControl as? ActorCommand.EffectFence)?.acknowledged?.isCancelled == true
            ) {
                pendingControl = null
            }

            var madeProgress: Boolean
            do {
                madeProgress = false
                val staged = stagedTransition
                if (staged != null) {
                    val required = staged.transition.effects.size
                    val replacement = if (staged.replacesCausalCredit) 1L else 0L
                    val nextCausalWork = causalWork - replacement + required.toLong()
                    if (
                        required <= effectCapacity - pendingEffects.size &&
                        nextCausalWork <= maximumCausalWork
                    ) {
                        commitTransition(context, staged, pendingEffects)
                        causalWork = nextCausalWork
                        inFlightEffects += required.toLong()
                        stagedTransition = null
                        madeProgress = true
                    } else if (nextCausalWork > maximumCausalWork && inFlightEffects == 0L) {
                        val failure = CausalEffectCapacityExceededException(
                            required = required,
                            available = maximumCausalWork - (causalWork - replacement),
                            maximum = maximumCausalWork,
                        )
                        staged.acknowledged?.completeExceptionally(failure)
                        throw failure
                    }
                }

                if (stagedTransition == null) {
                    val stagedEvent = when {
                        pendingLifecycleControl != null -> {
                            val command = requireNotNull(pendingLifecycleControl)
                            pendingLifecycleControl = null
                            StagedEvent(command.event, command.acknowledged, replacesCausalCredit = false)
                        }
                        settledEffectResults.isNotEmpty() -> StagedEvent(
                            event = settledEffectResults.removeFirst(),
                            acknowledged = null,
                            replacesCausalCredit = true,
                        )
                        pendingControl is ActorCommand.EffectCount -> {
                            val command = pendingControl
                            pendingControl = null
                            command.acknowledged.complete(context.registeredEffects)
                            madeProgress = true
                            null
                        }
                        pendingControl is ActorCommand.EffectFence -> {
                            val command = pendingControl
                            pendingControl = null
                            if (!command.acknowledged.isCancelled) {
                                pendingFence = EffectCommand.Fence(command.acknowledged)
                            }
                            madeProgress = true
                            null
                        }
                        asynchronousResults.isNotEmpty() &&
                            causalWork <= maximumCausalWork - effectCapacity.toLong() -> StagedEvent(
                                event = asynchronousResults.removeFirst(),
                                acknowledged = null,
                                replacesCausalCredit = false,
                            )
                        else -> null
                    }
                    if (stagedEvent != null) {
                        stagedTransition = stageTransition(stagedEvent, effectCapacity)
                        madeProgress = true
                    }
                }
            } while (madeProgress && context.scope.isActive)

            val input = select<ActorInput> {
                if (pendingLifecycleControl == null) {
                    context.lifecycleControls.onReceive { ActorInput.LifecycleCommand(it) }
                }
                if (pendingControl == null) {
                    context.controls.onReceive { ActorInput.Command(it) }
                }
                if (
                    pendingFence != null &&
                    stagedTransition == null &&
                    pendingEffects.isEmpty() &&
                    settledEffectResults.isEmpty() &&
                    asynchronousResults.isEmpty()
                ) {
                    // Once every earlier event is committed, queue the fence before accepting another
                    // settlement. The worker still settles its current effect before consuming the fence.
                    context.effects.onSend(requireNotNull(pendingFence)) {
                        ActorInput.FenceSubmitted
                    }
                }
                // Worker settlements are always serviceable: receiving one releases or replaces exactly
                // one in-flight causal credit, so staged transitions cannot deadlock the sole worker.
                context.effectSettlements.onReceive { ActorInput.Settlement(it) }
                if (asynchronousResults.size < limits.eventMailboxCapacity) {
                    context.results.onReceive { ActorInput.AsynchronousResult(it) }
                }
                if (pendingEffects.isNotEmpty()) {
                    context.effects.onSend(pendingEffects.first()) {
                        ActorInput.EffectSubmitted
                    }
                }
                if (
                    stagedTransition == null &&
                    pendingEffects.isEmpty() &&
                    settledEffectResults.isEmpty() &&
                    asynchronousResults.isEmpty() &&
                    pendingLifecycleControl == null &&
                    pendingControl == null &&
                    pendingFence == null &&
                    causalWork <= maximumCausalWork - effectCapacity.toLong()
                ) {
                    context.external.onReceive { ActorInput.External(it) }
                }
            }
            when (input) {
                is ActorInput.LifecycleCommand -> pendingLifecycleControl = input.command
                is ActorInput.Command -> pendingControl = input.command
                is ActorInput.Settlement -> {
                    check(inFlightEffects > 0L) { "Effect settled without an in-flight causal credit." }
                    inFlightEffects -= 1
                    if (input.settlement.event == null) {
                        causalWork -= 1
                    } else {
                        settledEffectResults.addLast(input.settlement.event)
                    }
                }
                is ActorInput.AsynchronousResult -> asynchronousResults.addLast(input.event)
                is ActorInput.External -> stagedTransition = stageTransition(
                    StagedEvent(input.envelope.event, input.envelope.acknowledged, false),
                    effectCapacity,
                )
                ActorInput.EffectSubmitted -> pendingEffects.removeFirst()
                ActorInput.FenceSubmitted -> pendingFence = null
            }
        }
    }

    private suspend fun reduceControl(
        context: RunContext,
        event: MeshEvent,
    ): Boolean {
        if (!context.actorJob.isActive) return false
        val acknowledged = CompletableDeferred<Unit>()
        return try {
            if (!sendLifecycleCommand(context, ActorCommand.Reduce(event, acknowledged))) return false
            select {
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
        } catch (cancelled: CancellationException) {
            acknowledged.cancel(cancelled)
            throw cancelled
        } finally {
            if (!acknowledged.isCompleted) acknowledged.cancel()
        }
    }

    private fun stageTransition(
        stagedEvent: StagedEvent,
        effectCapacity: Int,
    ): StagedTransition {
        val transition = try {
            engine.reduce(requireNotNull(mutableState.value), stagedEvent.event)
        } catch (failure: Throwable) {
            stagedEvent.acknowledged?.completeExceptionally(failure)
            throw failure
        }
        if (transition.effects.size > effectCapacity) {
            val failure = PendingEffectCapacityExceededException(
                required = transition.effects.size,
                available = effectCapacity,
                maximum = effectCapacity,
            )
            stagedEvent.acknowledged?.completeExceptionally(failure)
            throw failure
        }
        return StagedTransition(
            transition = transition,
            acknowledged = stagedEvent.acknowledged,
            replacesCausalCredit = stagedEvent.replacesCausalCredit,
        )
    }

    private fun commitTransition(
        context: RunContext,
        staged: StagedTransition,
        pendingEffects: ArrayDeque<EffectCommand.Execute>,
    ) {
        val transition = staged.transition
        val required = transition.effects.size
        check(required.toLong() <= Long.MAX_VALUE - context.registeredEffects) {
            "Registered effect count overflow."
        }
        val commands = transition.effects.map { effect ->
            EffectCommand.Execute(
                EffectEnvelope(
                    effect = effect,
                    observedAt = transition.state.observedAt,
                ),
            )
        }
        mutableState.value = transition.state
        publishTrace(context, transition)
        context.registeredEffects += required.toLong()
        pendingEffects.addAll(commands)
        staged.acknowledged?.complete(Unit)
    }

    private fun checkedCausalWorkCapacity(effectCapacity: Int): Long {
        val capacity = effectCapacity.toLong()
        check(capacity <= (Long.MAX_VALUE - 1L) / 2L) {
            "Effect capacity is too large for causal-work accounting."
        }
        return capacity * 2L + 1L
    }

    private fun reduceStoppingAfterActorTermination(
        context: RunContext,
        event: MeshEvent.RuntimeStopping,
    ) {
        val current = requireNotNull(mutableState.value)
        val transition = engine.reduce(current, event)
        // The worker is already terminated. RuntimeStopping effects follow the same explicit discard
        // policy as the actor-owned stopping path and are never registered during teardown.
        mutableState.value = transition.state
        publishTrace(context, transition)
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
                    val event = processEffect(context, command.envelope)
                    context.processedEffects += 1
                    context.effectSettlements.send(EffectSettlement(event))
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
    ): MeshEvent? =
        when (val effect = envelope.effect) {
            is MeshEffect.Schedule -> {
                scheduleTimer(context, envelope, effect)
                null
            }
            is MeshEffect.Cancel -> {
                cancelTimer(context, effect)
                null
            }
            is MeshEffect.ReinjectPacket -> {
                when (
                    val decoded = MeshProtocolAdapter.decode(
                        generation = effect.generation,
                        observedAt = envelope.observedAt,
                        source = effect.source,
                        bytes = effect.bytes,
                    )
                ) {
                    is PacketIngress.Accepted -> decoded.event
                    is PacketIngress.Rejected -> {
                        recordStructuralDecodeRejection()
                        null
                    }
                }
            }
            else -> executeEffect(executor, effect, envelope.observedAt)
        }

    private suspend fun awaitEffectFence(context: RunContext): Long? {
        if (!context.actorJob.isActive || !context.effectJob.isActive) return null
        val acknowledged = CompletableDeferred<Long>()
        return try {
            if (!sendActorCommand(context, ActorCommand.EffectFence(acknowledged))) return null
            select {
                acknowledged.onAwait { it }
                context.actorJob.onJoin {
                    if (acknowledged.isCompleted) acknowledged.await() else null
                }
                context.effectJob.onJoin {
                    if (acknowledged.isCompleted) acknowledged.await() else null
                }
            }
        } catch (cancelled: CancellationException) {
            acknowledged.cancel(cancelled)
            throw cancelled
        } finally {
            if (!acknowledged.isCompleted) acknowledged.cancel()
        }
    }

    private suspend fun awaitRegisteredEffectCount(context: RunContext): Long? {
        if (!context.actorJob.isActive) return null
        val acknowledged = CompletableDeferred<Long>()
        return try {
            if (!sendActorCommand(context, ActorCommand.EffectCount(acknowledged))) return null
            select {
                acknowledged.onAwait { it }
                context.actorJob.onJoin {
                    if (acknowledged.isCompleted) acknowledged.await() else null
                }
            }
        } catch (cancelled: CancellationException) {
            acknowledged.cancel(cancelled)
            throw cancelled
        } finally {
            if (!acknowledged.isCompleted) acknowledged.cancel()
        }
    }

    private suspend fun sendLifecycleCommand(
        context: RunContext,
        command: ActorCommand.Reduce,
    ): Boolean {
        if (!context.actorJob.isActive) return false
        return select {
            context.actorJob.onJoin { false }
            context.lifecycleControls.onSend(command) { true }
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
        val effectSettlements: Channel<EffectSettlement>,
        val lifecycleControls: Channel<ActorCommand.Reduce>,
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

        data class EffectFence(
            val acknowledged: CompletableDeferred<Long>,
        ) : ActorCommand
    }

    private sealed interface ActorInput {
        data class LifecycleCommand(
            val command: ActorCommand.Reduce,
        ) : ActorInput

        data class Command(
            val command: ActorCommand,
        ) : ActorInput

        data class Settlement(
            val settlement: EffectSettlement,
        ) : ActorInput

        data class AsynchronousResult(
            val event: MeshEvent,
        ) : ActorInput

        data class External(
            val envelope: ExternalEnvelope,
        ) : ActorInput

        data object EffectSubmitted : ActorInput

        data object FenceSubmitted : ActorInput
    }

    private sealed interface EffectCommand {
        data class Execute(
            val envelope: EffectEnvelope,
        ) : EffectCommand

        data class Fence(
            val acknowledged: CompletableDeferred<Long>,
        ) : EffectCommand
    }

    private data class EffectSettlement(
        val event: MeshEvent?,
    )

    private data class StagedEvent(
        val event: MeshEvent,
        val acknowledged: CompletableDeferred<Unit>?,
        val replacesCausalCredit: Boolean,
    )

    private data class StagedTransition(
        val transition: Transition<MeshState, MeshEffect>,
        val acknowledged: CompletableDeferred<Unit>?,
        val replacesCausalCredit: Boolean,
    )

    private data class ExternalEnvelope(
        val event: MeshEvent,
        val acknowledged: CompletableDeferred<Unit>?,
    )

    private sealed interface LinkIngress {
        data class Accepted(
            val event: MeshEvent,
        ) : LinkIngress

        data class Rejected(
            val result: SubmitResult.StructuralDecodeRejected,
        ) : LinkIngress
    }

    private data class EffectEnvelope(
        val effect: MeshEffect,
        val observedAt: MonotonicTime,
    )

    private data class TimerKey(
        val correlationValue: String,
        val timerId: TimerId,
    )

    private class PendingEffectCapacityExceededException(
        val required: Int,
        val available: Int,
        val maximum: Int,
    ) : IllegalStateException(
        "Pending effect capacity exceeded: required=$required, available=$available, maximum=$maximum.",
    )

    private class CausalEffectCapacityExceededException(
        val required: Int,
        val available: Long,
        val maximum: Long,
    ) : IllegalStateException(
        "Causal effect capacity exceeded: required=$required, available=$available, maximum=$maximum.",
    )

}
