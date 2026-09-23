package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.PacketId
import kotlin.time.Duration

data class PendingEventProjection(
    val id: ScheduledEventId,
    val deadline: MonotonicTime,
    val sequence: Long,
    val category: ScheduledCategory,
    val nodeId: SimulatedNodeId?,
    val linkId: SimulatedLinkId?,
    val packetId: PacketId?,
)

data class DeliveryProjection(
    val sequence: Long,
    val directionId: SimulatedLinkId,
    val sourceNode: SimulatedNodeId,
    val targetNode: SimulatedNodeId,
    val submittedAt: MonotonicTime,
    val deliveredAt: MonotonicTime,
    val byteCount: Int,
    val packetId: PacketId?,
    val ttl: UByte?,
    val packetClassification: String,
)

data class FaultCursor(
    val consumedTransmissionSelectors: List<TransmissionSelector> = emptyList(),
    val appliedTimedFaultIds: List<String> = emptyList(),
)

data class SimulationSnapshot(
    val now: MonotonicTime,
    val processedEvents: Long,
    val nodes: List<SimulatedNodeSnapshot>,
    val directions: List<DirectedSimulatedLink>,
    val publications: List<PublicationProjection>,
    val deliveries: List<DeliveryProjection>,
    val pendingEvents: List<PendingEventProjection>,
    val faultCursor: FaultCursor,
    val trace: List<SimulationTraceRecord>,
    val droppedTraceCount: Long,
) {
    fun node(id: SimulatedNodeId): SimulatedNodeSnapshot =
        nodes.single { it.id == id }
}

data class SimulationDiagnostics(
    val now: MonotonicTime,
    val processedEvents: Long,
    val pendingEventCount: Int,
    val lastTrace: List<SimulationTraceRecord>,
)

sealed interface QuiescenceResult {
    data class Quiescent(val snapshot: SimulationSnapshot) : QuiescenceResult
    data class EventLimitExceeded(val maximum: Int, val diagnostics: SimulationDiagnostics) : QuiescenceResult
}

sealed interface RunUntilResult {
    data class Reached(val snapshot: SimulationSnapshot) : RunUntilResult
    data class Unreachable(val snapshot: SimulationSnapshot) : RunUntilResult
    data class EventLimitExceeded(val maximum: Int, val diagnostics: SimulationDiagnostics) : RunUntilResult
    data class VirtualDurationExceeded(val maximum: Duration, val diagnostics: SimulationDiagnostics) : RunUntilResult
}
