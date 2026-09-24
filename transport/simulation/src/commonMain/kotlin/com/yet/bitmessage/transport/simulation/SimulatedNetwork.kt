package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshEffect
import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerDriver
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerDriverFactory
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerKey
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerRequest
import com.yet.bitmessage.engine.mesh.runtime.RuntimeQuiescenceResult
import com.yet.bitmessage.engine.mesh.runtime.StartResult
import com.yet.bitmessage.engine.mesh.runtime.StopResult
import com.yet.bitmessage.engine.mesh.runtime.SubmitResult
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketIdentity
import com.yet.bitmessage.protocol.bitchat.SigningTranscript
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkCloseReason
import com.yet.bitmessage.transport.api.LinkCommand
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * A single virtual-time authority around real per-node mesh runtimes.
 *
 * Never hold [mutex] while waiting for a runtime acknowledgement or causal fence: effect providers
 * need to register their results in this queue before those waits can complete.
 */
class SimulatedNetwork(
    private val parentScope: CoroutineScope,
    private val limits: SimulationLimits = SimulationLimits(),
    private val faultPlan: FaultPlan = FaultPlan(),
) {
    private val mutex = Mutex()
    private val queue = VirtualEventQueue<SimulationEvent>(limits.maxScheduledEvents)
    private val nodes = mutableMapOf<SimulatedNodeId, SimulatedNode>()
    private val connections = mutableMapOf<String, SimulatedConnection>()
    private val directions = mutableMapOf<SimulatedLinkId, DirectedSimulatedLink>()
    private val writeOrdinals = mutableMapOf<SimulatedLinkId, Long>()
    private val timers = mutableMapOf<Pair<SimulatedNodeId, MeshTimerKey>, TimerRegistration>()
    private val consumedTransmissionSelectors = mutableSetOf<TransmissionSelector>()
    private val appliedTimedFaultIds = mutableSetOf<String>()
    private val deliveries = mutableListOf<DeliveryProjection>()
    private val linkCompletions = mutableListOf<LinkCompletionProjection>()
    private val trace = BoundedSimulationTrace(limits.maxTraceRecords)
    private var traceSequence = 0L
    private var fatalLimit: SimulationLimitExceededException? = null
    private var closed = false
    private var currentTime = MonotonicTime.ZERO
    private var processed = 0L

    val now: MonotonicTime get() = currentTime

    init {
        if (faultPlan.transmissionFaults.size + faultPlan.timedLinkFaults.size > limits.maxFaultActions) {
            failLimit("Simulation fault-action limit exceeded.")
        }
        val timed = faultPlan.timedLinkFaults
        requireQueueSpaceLocked(timed.size)
        timed.forEach { fault ->
            scheduleLocked(fault.deadline, SimulationEvent.ApplyLinkFault(fault.id))
        }
    }

    private val effectHost = object : SimulationEffectHost {
        override val now: MonotonicTime get() = this@SimulatedNetwork.now

        override suspend fun scheduleMeshEvent(
            nodeId: SimulatedNodeId,
            deadline: MonotonicTime,
            event: MeshEvent,
        ) {
            mutex.withLock {
                checkNotClosed()
                scheduleLocked(deadline, SimulationEvent.DeliverMeshEvent(nodeId, event))
            }
        }

        override suspend fun submitWrite(nodeId: SimulatedNodeId, command: LinkCommand.Write) {
            submitTransmission(nodeId, command.linkId, command.bytes, command)
        }

        override suspend fun submitClose(nodeId: SimulatedNodeId, command: LinkCommand.Close) {
            closeDirection(nodeId, command.linkId, command.reason)
        }

        override suspend fun recordPublication(nodeId: SimulatedNodeId, effect: MeshEffect.PublishPublicPayload) {
            mutex.withLock {
                try {
                    requireNotNull(nodes[nodeId]) { "Unknown simulated node $nodeId." }.record(effect)
                } catch (limit: SimulationLimitExceededException) {
                    rememberFatal(limit)
                    throw limit
                }
            }
        }
    }

    suspend fun addNode(config: SimulatedNodeConfig): SimulatedNode {
        val node = mutex.withLock {
            checkNotClosed()
            require(config.id !in nodes) { "Duplicate simulated node ID: ${config.id}." }
            if (nodes.size >= limits.maxNodes) failLimit("Simulation node limit exceeded.")
            SimulatedNode(
                config = config,
                parentScope = parentScope,
                host = effectHost,
                timerDriverFactory = MeshTimerDriverFactory { NetworkTimerDriver(config.id) },
                maximumPublications = limits.maxPublicationRecords,
            ).also { nodes[config.id] = it }
        }
        try {
            node.start(now)
        } catch (failure: Throwable) {
            mutex.withLock {
                if (nodes[config.id] === node) nodes.remove(config.id)
            }
            throw failure
        }
        return node
    }

    suspend fun connect(connection: SimulatedConnection): SimulatedConnection = mutex.withLock {
        checkNotClosed()
        require(connection.id !in connections) { "Duplicate simulated connection ID: ${connection.id}." }
        require(connection.aToB.source.nodeId in nodes && connection.aToB.target.nodeId in nodes) {
            "Both connection endpoints must exist."
        }
        val pair = listOf(connection.aToB, connection.bToA)
        require(pair.none { it.id in directions }) { "Duplicate directed link ID." }
        if (directions.size > limits.maxDirectedLinks - pair.size) {
            failLimit("Simulation directed-link limit exceeded.")
        }
        requireQueueSpaceLocked(pair.size)
        connections[connection.id] = connection
        pair.forEach { direction ->
            directions[direction.id] = direction
            scheduleLocked(now, SimulationEvent.ObserveLink(
                direction.source.nodeId,
                LinkEvent.Opened(direction.source.linkId, capabilities(direction)),
            ))
        }
        connection
    }

    suspend fun stopNode(id: SimulatedNodeId): StopResult {
        val node = mutex.withLock {
            checkNotClosed()
            requireNode(id)
        }
        return node.stop(now)
    }

    suspend fun startNode(id: SimulatedNodeId): StartResult {
        val node = mutex.withLock {
            checkNotClosed()
            requireNode(id)
        }
        return node.start(now)
    }

    /** An external transport write; unlike a runtime-originated write, it has no completion event. */
    suspend fun injectTransportWrite(directionId: SimulatedLinkId, bytes: Bytes): DirectedWriteDecision =
        submitTransmission(null, null, bytes, null, directionId)

    suspend fun runCurrentUntilQuiescent(
        maxProcessedEvents: Int = limits.maxProcessedEvents,
    ): QuiescenceResult {
        require(maxProcessedEvents > 0)
        val starting = processed
        while (true) {
            throwIfFatal()
            val orderedNodes = mutex.withLock { nodes.values.sortedBy { it.config.id } }
            for (node in orderedNodes) {
                val remaining = (maxProcessedEvents.toLong() - (processed - starting)).coerceAtLeast(1)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                when (val result = node.runtime.awaitImmediateQuiescence(remaining)) {
                    is RuntimeQuiescenceResult.Quiescent -> {
                        processed = checkedAdd(processed, result.processedEffects)
                        if (processed - starting > maxProcessedEvents) {
                            return QuiescenceResult.EventLimitExceeded(maxProcessedEvents, diagnostics())
                        }
                    }
                    is RuntimeQuiescenceResult.LimitExceeded -> {
                        processed = checkedAdd(processed, result.processedEffects)
                        return QuiescenceResult.EventLimitExceeded(maxProcessedEvents, diagnostics())
                    }
                    RuntimeQuiescenceResult.Closed -> Unit
                }
            }
            throwIfFatal()
            val due = mutex.withLock {
                if (queue.peek()?.deadline == now && processed - starting >= maxProcessedEvents) {
                    return QuiescenceResult.EventLimitExceeded(maxProcessedEvents, diagnosticsLocked())
                }
                queue.removeNextDue(now)
            }
                ?: return QuiescenceResult.Quiescent(snapshot())
            processed = checkedAdd(processed, 1)
            dispatch(due)
        }
    }

    suspend fun advanceTo(
        target: MonotonicTime,
        maxProcessedEvents: Int = limits.maxProcessedEvents,
    ): QuiescenceResult {
        require(target >= now) { "Virtual time cannot move backwards." }
        require(maxProcessedEvents > 0)
        val starting = processed
        // Runtime effects accepted at the current instant may not yet have registered their
        // scheduled completions. Fence them before choosing a future deadline.
        when (val settled = runCurrentUntilQuiescent(maxProcessedEvents)) {
            is QuiescenceResult.EventLimitExceeded ->
                return QuiescenceResult.EventLimitExceeded(maxProcessedEvents, settled.diagnostics)
            is QuiescenceResult.Quiescent -> Unit
        }
        while (true) {
            val next = mutex.withLock { queue.peek()?.deadline } ?: break
            if (next > target) break
            check(next >= now) { "A virtual event was scheduled in the past." }
            currentTime = next
            val remaining = (maxProcessedEvents.toLong() - (processed - starting)).coerceAtLeast(1)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val result = runCurrentUntilQuiescent(remaining)) {
                is QuiescenceResult.EventLimitExceeded ->
                    return QuiescenceResult.EventLimitExceeded(maxProcessedEvents, result.diagnostics)
                is QuiescenceResult.Quiescent -> Unit
            }
            if (processed - starting > maxProcessedEvents) {
                return QuiescenceResult.EventLimitExceeded(maxProcessedEvents, diagnostics())
            }
        }
        currentTime = target
        val remaining = (maxProcessedEvents.toLong() - (processed - starting)).coerceAtLeast(1)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when (val result = runCurrentUntilQuiescent(remaining)) {
            is QuiescenceResult.Quiescent ->
                if (processed - starting > maxProcessedEvents) {
                    QuiescenceResult.EventLimitExceeded(maxProcessedEvents, diagnostics())
                } else result
            is QuiescenceResult.EventLimitExceeded ->
                QuiescenceResult.EventLimitExceeded(maxProcessedEvents, result.diagnostics)
        }
    }

    suspend fun advanceBy(
        duration: Duration,
        maxProcessedEvents: Int = limits.maxProcessedEvents,
    ): QuiescenceResult = advanceTo(now.plus(duration), maxProcessedEvents)

    suspend fun runUntil(
        predicate: (SimulationSnapshot) -> Boolean,
        maxProcessedEvents: Int = limits.maxProcessedEvents,
        maxVirtualDuration: Duration = limits.maxVirtualDuration,
    ): RunUntilResult {
        require(maxProcessedEvents > 0)
        require(maxVirtualDuration.isFinite() && maxVirtualDuration >= Duration.ZERO)
        val startTime = now
        val startCount = processed
        while (true) {
            val remaining = (maxProcessedEvents.toLong() - (processed - startCount)).coerceAtLeast(1)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val result = runCurrentUntilQuiescent(remaining)) {
                is QuiescenceResult.EventLimitExceeded ->
                    return RunUntilResult.EventLimitExceeded(maxProcessedEvents, result.diagnostics)
                is QuiescenceResult.Quiescent -> Unit
            }
            if (processed - startCount > maxProcessedEvents) {
                return RunUntilResult.EventLimitExceeded(maxProcessedEvents, diagnostics())
            }
            val stable = snapshot()
            if (predicate(stable)) return RunUntilResult.Reached(stable)
            val next = mutex.withLock { queue.peek()?.deadline }
                ?: return RunUntilResult.Unreachable(stable)
            if (next.elapsedSince(startTime) > maxVirtualDuration) {
                return RunUntilResult.VirtualDurationExceeded(maxVirtualDuration, diagnostics())
            }
            val advanceBudget = maxProcessedEvents.toLong() - (processed - startCount)
            if (advanceBudget <= 0) {
                return RunUntilResult.EventLimitExceeded(maxProcessedEvents, diagnostics())
            }
            when (val result = advanceTo(next, advanceBudget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) {
                is QuiescenceResult.EventLimitExceeded ->
                    return RunUntilResult.EventLimitExceeded(maxProcessedEvents, result.diagnostics)
                is QuiescenceResult.Quiescent -> Unit
            }
        }
    }

    suspend fun snapshot(): SimulationSnapshot {
        throwIfFatal()
        val captured = mutex.withLock {
            SnapshotParts(
                nodes.values.sortedBy { it.config.id },
                directions.values.sortedBy { it.id },
                deliveries.toList(),
                linkCompletions.toList(),
                queue.entries().map { pendingProjection(it) },
                FaultCursor(
                    consumedTransmissionSelectors.sortedWith(
                        compareBy<TransmissionSelector> { it.directionId }.thenBy { it.writeOrdinal },
                    ),
                    appliedTimedFaultIds.sorted(),
                ),
                trace.records(),
                trace.droppedCount,
            )
        }
        val nodeSnapshots = captured.nodes.map { it.snapshot() }
        throwIfFatal()
        return SimulationSnapshot(
            now = now,
            processedEvents = processed,
            nodes = nodeSnapshots,
            directions = captured.directions,
            publications = nodeSnapshots.flatMap { it.publications },
            deliveries = captured.deliveries,
            linkCompletions = captured.completions,
            pendingEvents = captured.pending,
            faultCursor = captured.cursor,
            trace = captured.trace,
            droppedTraceCount = captured.droppedTrace,
        )
    }

    suspend fun close() {
        val ordered = mutex.withLock {
            if (closed) return
            closed = true
            nodes.values.sortedBy { it.config.id }
        }
        ordered.forEach { it.close(now) }
    }

    private suspend fun submitTransmission(
        sourceNode: SimulatedNodeId?,
        sourceLinkId: LinkId?,
        bytes: Bytes,
        command: LinkCommand.Write?,
        explicitDirectionId: SimulatedLinkId? = null,
    ): DirectedWriteDecision = mutex.withLock {
        checkNotClosed()
        val direction = if (explicitDirectionId != null) {
            requireNotNull(directions[explicitDirectionId]) { "Unknown directed link $explicitDirectionId." }
        } else {
            requireNotNull(directions.values.singleOrNull {
                it.source.nodeId == sourceNode && it.source.linkId == sourceLinkId
            }) { "No directed link for source $sourceNode/$sourceLinkId." }
        }
        val ordinal = checkedAdd(writeOrdinals[direction.id] ?: 0, 1)
        val selector = TransmissionSelector(direction.id, ordinal)
        val fault = faultPlan.decision(direction.id, ordinal)
        val decision = direction.decide(bytes.size)
        if (fault is TransmissionFault.CorruptByte) {
            require(fault.offset < bytes.size) { "Corruption offset exceeds packet length." }
        }
        val forced = if (decision == DirectedWriteDecision.Accepted &&
            fault is TransmissionFault.CompleteWith
        ) fault.result else null
        val outcome = forced?.toDecision() ?: decision
        val copyCount = if (outcome == DirectedWriteDecision.Accepted) {
            if (fault is TransmissionFault.Drop) 0
            else if (fault is TransmissionFault.Duplicate) fault.copies else 1
        } else 0
        val required = copyCount + if (command == null) 0 else 1
        requireQueueSpaceLocked(required)
        val submittedAt = now
        val completionAt = now.plus(direction.latency)
        val deliveryAt = if (fault is TransmissionFault.AddDelay) {
            completionAt.plus(fault.delay)
        } else completionAt
        val deliveredBytes = if (fault is TransmissionFault.CorruptByte) {
            bytes.copyToByteArray().also {
                it[fault.offset] = (it[fault.offset].toInt() xor fault.xorMask.toInt()).toByte()
            }.let(Bytes::copyOf)
        } else bytes
        val metadata = packetMetadata(deliveredBytes)
        // All validation and capacity checks precede ordinal/fault consumption and queue insertion.
        writeOrdinals[direction.id] = ordinal
        if (fault != null) consumedTransmissionSelectors += selector
        if (command != null) {
            val result = forced?.toLinkResult(command) ?: outcome.toLinkResult(command)
            scheduleLocked(completionAt, SimulationEvent.DeliverMeshEvent(
                direction.source.nodeId,
                MeshEvent.LinkCompleted(command.correlationId, command.generation, completionAt, result),
            ))
        }
        repeat(copyCount) {
            scheduleLocked(deliveryAt, SimulationEvent.DeliverPayload(
                directionId = direction.id,
                targetNode = direction.target.nodeId,
                targetLinkId = direction.target.linkId,
                epoch = direction.epoch,
                bytes = deliveredBytes,
                submittedAt = submittedAt,
                packetId = metadata.packetId,
                ttl = metadata.ttl,
                packetClassification = metadata.classification,
                wireSha256 = metadata.wireSha256,
                signatureSha256 = metadata.signatureSha256,
                signingTranscriptSha256 = metadata.signingTranscriptSha256,
            ))
        }
        outcome
    }

    private suspend fun closeDirection(nodeId: SimulatedNodeId, linkId: LinkId, reason: LinkCloseReason) {
        mutex.withLock {
            val direction = requireNotNull(directions.values.singleOrNull {
                it.source.nodeId == nodeId && it.source.linkId == linkId
            }) { "Unknown outgoing link $nodeId/$linkId." }
            requireQueueSpaceLocked(1)
            val updated = direction.copy(open = false, epoch = checkedAdd(direction.epoch, 1))
            directions[direction.id] = updated
            scheduleLocked(now, SimulationEvent.ObserveLink(
                nodeId,
                LinkEvent.Closed(linkId, reason),
            ))
        }
    }

    private suspend fun dispatch(scheduled: ScheduledEvent<SimulationEvent>) {
        when (val event = scheduled.value) {
            is SimulationEvent.DeliverPayload -> {
                val valid = mutex.withLock {
                    directions[event.directionId]?.let { it.open && it.epoch == event.epoch } == true
                }
                if (!valid) return
                val result = requireNode(event.targetNode).runtime.submitAndAwait(
                    LinkEvent.PayloadReceived(event.targetLinkId, event.bytes), now,
                )
                when (result) {
                    SubmitResult.Accepted -> recordTrace(TraceCategory.PAYLOAD_DELIVERED, TraceOutcome.DELIVERED,
                        event.targetNode, event.directionId, event.packetId, event.bytes.size, event.ttl)
                    is SubmitResult.StructuralDecodeRejected ->
                        recordTrace(TraceCategory.DECODE_REJECTED, TraceOutcome.REJECTED,
                            event.targetNode, event.directionId, event.packetId, event.bytes.size, event.ttl)
                    SubmitResult.Closed ->
                        recordTrace(TraceCategory.PAYLOAD_DELIVERED, TraceOutcome.REJECTED,
                            event.targetNode, event.directionId, event.packetId, event.bytes.size, event.ttl)
                    else -> failLimit("Mesh runtime rejected payload delivery: $result")
                }
                mutex.withLock {
                    if (deliveries.size >= limits.maxDeliveryRecords) failLimit("Simulation delivery-record limit exceeded.")
                    val direction = requireNotNull(directions[event.directionId])
                    deliveries += DeliveryProjection(
                        sequence = scheduled.sequence,
                        directionId = event.directionId,
                        sourceNode = direction.source.nodeId,
                        targetNode = event.targetNode,
                        submittedAt = event.submittedAt,
                        deliveredAt = now,
                        byteCount = event.bytes.size,
                        packetId = event.packetId,
                        ttl = event.ttl,
                        packetClassification = event.packetClassification,
                        wireSha256 = event.wireSha256,
                        signatureSha256 = event.signatureSha256,
                        signingTranscriptSha256 = event.signingTranscriptSha256,
                    )
                }
            }
            is SimulationEvent.DeliverMeshEvent -> {
                if (!requireAcceptedOrStopped(requireNode(event.targetNode).runtime.submitAndAwait(event.event))) {
                    recordTrace(TraceCategory.ASYNC_RESULT, TraceOutcome.REJECTED, event.targetNode)
                } else if (event.event is MeshEvent.LinkCompleted) {
                    mutex.withLock {
                        if (linkCompletions.size >= limits.maxCompletionRecords) {
                            failLimit("Simulation completion-record limit exceeded.")
                        }
                        linkCompletions += LinkCompletionProjection(
                            event.targetNode,
                            event.event.observedAt,
                            event.event.result,
                        )
                    }
                }
            }
            is SimulationEvent.ObserveLink -> {
                if (!requireAcceptedOrStopped(requireNode(event.targetNode).runtime.submitAndAwait(event.event, now))) {
                    recordTrace(TraceCategory.LINK_OBSERVED, TraceOutcome.REJECTED, event.targetNode)
                }
            }
            is SimulationEvent.FireRuntimeTimer -> {
                val request = mutex.withLock {
                    val key = event.targetNode to event.key
                    timers[key]?.takeIf { it.id == scheduled.id }?.also {
                        timers.remove(key)
                        it.owner.onRemoved(event.key)
                    }?.request
                }
                request?.onElapsed?.invoke()
                recordTrace(TraceCategory.TIMER_FIRED, TraceOutcome.COMPLETED, event.targetNode)
            }
            is SimulationEvent.ApplyLinkFault -> applyTimedFault(event.actionId)
        }
    }

    private suspend fun applyTimedFault(id: String) {
        mutex.withLock {
            val fault = requireNotNull(faultPlan.timedFault(id)) { "Unknown timed fault $id." }
            check(id !in appliedTimedFaultIds) { "Timed fault applied twice: $id." }
            val affected = when (val action = fault.action) {
                is LinkFaultAction.SetReadiness -> listOf(requireDirection(action.directionId))
                is LinkFaultAction.SetOpen -> listOf(requireDirection(action.directionId))
                is LinkFaultAction.Partition -> action.directionIds.map(::requireDirection)
                is LinkFaultAction.Reconnect -> {
                    val connection = requireNotNull(connections[action.connectionId])
                    listOf(requireDirection(connection.aToB.id), requireDirection(connection.bToA.id))
                }
            }.sortedBy { it.id }
            requireQueueSpaceLocked(affected.size)
            check(affected.all { it.epoch < Long.MAX_VALUE }) { "Simulated link epoch exhausted." }
            affected.forEach { direction ->
                val updated = when (val action = fault.action) {
                    is LinkFaultAction.SetReadiness -> direction.copy(writeReady = action.ready)
                    is LinkFaultAction.SetOpen ->
                        direction.copy(open = action.open, epoch = checkedAdd(direction.epoch, 1))
                    is LinkFaultAction.Partition ->
                        direction.copy(open = !action.active, epoch = checkedAdd(direction.epoch, 1))
                    is LinkFaultAction.Reconnect ->
                        direction.copy(open = true, writeReady = true, epoch = checkedAdd(direction.epoch, 1))
                }
                directions[updated.id] = updated
                val observation = when (fault.action) {
                    is LinkFaultAction.SetReadiness ->
                        LinkEvent.ReadinessChanged(updated.source.linkId, capabilities(updated))
                    else -> if (updated.open) {
                        LinkEvent.Opened(updated.source.linkId, capabilities(updated))
                    } else LinkEvent.Closed(updated.source.linkId, LinkCloseReason.REMOTE_CLOSED)
                }
                scheduleLocked(now, SimulationEvent.ObserveLink(updated.source.nodeId, observation))
            }
            appliedTimedFaultIds += id
        }
        recordTrace(TraceCategory.FAULT_APPLIED, TraceOutcome.APPLIED)
    }

    private inner class NetworkTimerDriver(private val nodeId: SimulatedNodeId) : MeshTimerDriver {
        private val owned = mutableSetOf<MeshTimerKey>()
        private var stopped = false

        override suspend fun schedule(request: MeshTimerRequest) {
            mutex.withLock {
                if (stopped) return
                val key = nodeId to request.key
                if (request.deadline < now) failLimit("Runtime timer deadline is in the past.")
                if (traceSequence == Long.MAX_VALUE) failLimit("Simulation trace sequence exhausted.")
                val existing = timers[key]
                when (queue.insertionFailure(1)) {
                    QueueScheduleResult.SequenceExhausted -> failLimit("Simulation event sequence exhausted.")
                    QueueScheduleResult.Full -> if (existing == null || !queue.contains(existing.id)) {
                        failLimit("Simulation scheduled-event limit exceeded.")
                    }
                    null -> Unit
                    is QueueScheduleResult.Scheduled -> error("Queue preflight cannot schedule an event.")
                }
                timers.remove(key)?.let {
                    queue.cancel(it.id)
                    it.owner.onRemoved(request.key)
                }
                val id = scheduleLocked(request.deadline, SimulationEvent.FireRuntimeTimer(nodeId, request.key))
                timers[key] = TimerRegistration(id, request, this)
                owned += request.key
            }
        }

        override suspend fun cancel(key: MeshTimerKey) {
            mutex.withLock {
                if (owned.remove(key)) {
                    val registrationKey = nodeId to key
                    timers[registrationKey]?.takeIf { it.owner === this }?.let {
                        timers.remove(registrationKey)
                        queue.cancel(it.id)
                    }
                }
            }
        }

        override suspend fun cancelAll() {
            mutex.withLock {
                stopped = true
                owned.forEach { key ->
                    val registrationKey = nodeId to key
                    timers[registrationKey]?.takeIf { it.owner === this }?.let {
                        timers.remove(registrationKey)
                        queue.cancel(it.id)
                    }
                }
                owned.clear()
            }
        }

        fun onRemoved(key: MeshTimerKey) {
            owned.remove(key)
        }
    }

    private fun requireNode(id: SimulatedNodeId): SimulatedNode =
        requireNotNull(nodes[id]) { "Unknown simulated node $id." }

    private fun requireDirection(id: SimulatedLinkId): DirectedSimulatedLink =
        requireNotNull(directions[id]) { "Unknown directed link $id." }

    /** Runtime suspension drops scheduled observations; it is not simulator resource exhaustion. */
    private fun requireAcceptedOrStopped(result: SubmitResult): Boolean {
        when (result) {
            SubmitResult.Accepted -> return true
            SubmitResult.Closed -> return false
            is SubmitResult.StructuralDecodeRejected ->
                error("Structural decode rejection is only valid for payload ingress.")
            else -> failLimit("Mesh runtime rejected scheduled event: $result")
        }
    }

    private fun requireQueueSpaceLocked(count: Int) {
        when (queue.insertionFailure(count)) {
            QueueScheduleResult.Full -> failLimit("Simulation scheduled-event limit exceeded.")
            QueueScheduleResult.SequenceExhausted -> failLimit("Simulation event sequence exhausted.")
            null -> Unit
            is QueueScheduleResult.Scheduled -> error("Queue preflight cannot schedule an event.")
        }
    }

    private fun scheduleLocked(deadline: MonotonicTime, event: SimulationEvent): ScheduledEventId {
        require(deadline >= now) { "A virtual event cannot be scheduled in the past." }
        val outcome = queue.schedule(deadline, event)
        val id = when (outcome) {
            is QueueScheduleResult.Scheduled -> outcome.id
            QueueScheduleResult.Full -> failLimit("Simulation scheduled-event limit exceeded.")
            QueueScheduleResult.SequenceExhausted -> failLimit("Simulation event sequence exhausted.")
        }
        appendTraceLocked(TraceCategory.SCHEDULED, TraceOutcome.SCHEDULED)
        return id
    }

    private fun pendingProjection(entry: ScheduledEvent<SimulationEvent>): PendingEventProjection {
        val event = entry.value
        val node = when (event) {
            is SimulationEvent.DeliverPayload -> event.targetNode
            is SimulationEvent.DeliverMeshEvent -> event.targetNode
            is SimulationEvent.ObserveLink -> event.targetNode
            is SimulationEvent.FireRuntimeTimer -> event.targetNode
            is SimulationEvent.ApplyLinkFault -> null
        }
        return PendingEventProjection(
            id = entry.id,
            deadline = entry.deadline,
            sequence = entry.sequence,
            category = event.category,
            nodeId = node,
            linkId = (event as? SimulationEvent.DeliverPayload)?.directionId,
            packetId = (event as? SimulationEvent.DeliverPayload)?.packetId,
        )
    }

    private fun packetMetadata(bytes: Bytes): PacketMetadata =
        when (val decoded = BitchatCodec.decode(bytes)) {
            is DecodeResult.Failure -> PacketMetadata(
                null, null, "structurally-invalid", SimulationSha256.digest(bytes), null, null,
            )
            is DecodeResult.Success -> {
                val packet = decoded.value
                val transcript = if (packet.signature != null) {
                    (SigningTranscript.build(packet) as? DecodeResult.Success)?.value
                } else null
                PacketMetadata(
                    PacketIdentity.fromSha256(SimulationSha256.digest(PacketIdentity.input(packet).canonicalBytes)),
                    packet.ttl,
                    packet.type.value.toString(),
                    SimulationSha256.digest(bytes),
                    packet.signature?.let(SimulationSha256::digest),
                    transcript?.let(SimulationSha256::digest),
                )
            }
        }

    private fun DirectedWriteDecision.toLinkResult(command: LinkCommand.Write): LinkResult = when (this) {
        DirectedWriteDecision.Accepted -> LinkResult.Written(command.linkId, command.correlationId, command.generation)
        DirectedWriteDecision.Backpressured ->
            LinkResult.Backpressured(command.linkId, command.correlationId, command.generation)
        DirectedWriteDecision.Disconnected ->
            LinkResult.Disconnected(command.linkId, command.correlationId, command.generation)
        DirectedWriteDecision.Unsupported ->
            LinkResult.Unsupported(command.linkId, command.correlationId, command.generation)
        is DirectedWriteDecision.Failed ->
            LinkResult.Failed(command.linkId, command.correlationId, command.generation, code)
        is DirectedWriteDecision.PayloadTooLarge ->
            LinkResult.PayloadTooLarge(command.linkId, command.correlationId, command.generation, maximumBytes)
    }

    private fun PlannedLinkResult.toDecision(): DirectedWriteDecision = when (this) {
        PlannedLinkResult.Backpressured -> DirectedWriteDecision.Backpressured
        PlannedLinkResult.Disconnected -> DirectedWriteDecision.Disconnected
        PlannedLinkResult.Unsupported -> DirectedWriteDecision.Unsupported
        is PlannedLinkResult.Failed -> DirectedWriteDecision.Failed(code)
    }

    private fun PlannedLinkResult.toLinkResult(command: LinkCommand.Write): LinkResult = when (this) {
        PlannedLinkResult.Backpressured ->
            LinkResult.Backpressured(command.linkId, command.correlationId, command.generation)
        PlannedLinkResult.Disconnected ->
            LinkResult.Disconnected(command.linkId, command.correlationId, command.generation)
        PlannedLinkResult.Unsupported ->
            LinkResult.Unsupported(command.linkId, command.correlationId, command.generation)
        is PlannedLinkResult.Failed ->
            LinkResult.Failed(command.linkId, command.correlationId, command.generation, code)
    }

    private fun capabilities(direction: DirectedSimulatedLink): LinkCapabilities =
        LinkCapabilities(direction.mtu, direction.writeReady && direction.open)

    private fun checkNotClosed() {
        throwIfFatal()
        check(!closed) { "Simulation network is closed." }
    }

    private fun rememberFatal(failure: SimulationLimitExceededException) {
        if (fatalLimit == null) fatalLimit = failure
    }

    private fun failLimit(message: String): Nothing {
        val failure = SimulationLimitExceededException(message)
        rememberFatal(failure)
        throw failure
    }

    private fun throwIfFatal() {
        fatalLimit?.let { throw it }
    }

    private fun appendTraceLocked(category: TraceCategory, outcome: TraceOutcome) {
        check(traceSequence < Long.MAX_VALUE) { "Simulation trace sequence exhausted." }
        trace.append(SimulationTraceRecord(now, traceSequence++, category = category, outcome = outcome))
    }

    private suspend fun recordTrace(
        category: TraceCategory,
        outcome: TraceOutcome,
        nodeId: SimulatedNodeId? = null,
        linkId: SimulatedLinkId? = null,
        packetId: PacketId? = null,
        byteCount: Int? = null,
        ttl: UByte? = null,
    ) {
        mutex.withLock {
            check(traceSequence < Long.MAX_VALUE) { "Simulation trace sequence exhausted." }
            trace.append(SimulationTraceRecord(
                time = now,
                sequence = traceSequence++,
                nodeId = nodeId,
                linkId = linkId,
                category = category,
                packetIdPrefix = packetId?.let(::packetPrefix),
                byteCount = byteCount,
                ttl = ttl,
                outcome = outcome,
            ))
        }
    }

    private fun packetPrefix(id: PacketId): String {
        val digits = "0123456789abcdef"
        return buildString {
            id.value.copyToByteArray().take(4).forEach { byte ->
                append(digits[(byte.toInt() ushr 4) and 0xf])
                append(digits[byte.toInt() and 0xf])
            }
        }
    }

    /** Redacted diagnostics remain readable after a sticky simulator limit failure. */
    suspend fun diagnostics(): SimulationDiagnostics = mutex.withLock { diagnosticsLocked() }

    private fun diagnosticsLocked(): SimulationDiagnostics =
        SimulationDiagnostics(now, processed, queue.size, trace.records().takeLast(64))

    private fun checkedAdd(current: Long, increment: Long): Long {
        check(increment >= 0 && current <= Long.MAX_VALUE - increment) {
            "Simulation counter exhausted."
        }
        return current + increment
    }

    private data class TimerRegistration(
        val id: ScheduledEventId,
        val request: MeshTimerRequest,
        val owner: NetworkTimerDriver,
    )
    private data class PacketMetadata(
        val packetId: PacketId?,
        val ttl: UByte?,
        val classification: String,
        val wireSha256: Bytes,
        val signatureSha256: Bytes?,
        val signingTranscriptSha256: Bytes?,
    )
    private data class SnapshotParts(
        val nodes: List<SimulatedNode>,
        val directions: List<DirectedSimulatedLink>,
        val deliveries: List<DeliveryProjection>,
        val completions: List<LinkCompletionProjection>,
        val pending: List<PendingEventProjection>,
        val cursor: FaultCursor,
        val trace: List<SimulationTraceRecord>,
        val droppedTrace: Long,
    )
}
