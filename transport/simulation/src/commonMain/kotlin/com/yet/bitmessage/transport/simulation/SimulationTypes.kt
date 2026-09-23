package com.yet.bitmessage.transport.simulation

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@JvmInline
value class SimulatedNodeId private constructor(val value: String) : Comparable<SimulatedNodeId> {
    override fun compareTo(other: SimulatedNodeId): Int = value.compareTo(other.value)

    companion object {
        fun of(value: String): SimulatedNodeId =
            SimulatedNodeId(value.trim().also { require(it.isNotEmpty()) })
    }
}

@JvmInline
value class SimulatedLinkId private constructor(val value: String) : Comparable<SimulatedLinkId> {
    override fun compareTo(other: SimulatedLinkId): Int = value.compareTo(other.value)

    companion object {
        fun of(value: String): SimulatedLinkId =
            SimulatedLinkId(value.trim().also { require(it.isNotEmpty()) })
    }
}

@JvmInline
value class ScheduledEventId internal constructor(val value: Long) {
    init {
        require(value >= 0)
    }
}

data class SimulationLimits(
    val maxNodes: Int = 16,
    val maxDirectedLinks: Int = 64,
    val maxScheduledEvents: Int = 4_096,
    val maxFaultActions: Int = 2_048,
    val maxTraceRecords: Int = 8_192,
    val maxPublicationRecords: Int = 1_024,
    val maxDeliveryRecords: Int = 8_192,
    val maxProcessedEvents: Int = 100_000,
    val maxVirtualDuration: Duration = 10.minutes,
) {
    init {
        require(maxNodes > 0 && maxDirectedLinks > 0 && maxScheduledEvents > 0)
        require(maxFaultActions > 0 && maxTraceRecords > 0)
        require(maxPublicationRecords > 0 && maxDeliveryRecords > 0 && maxProcessedEvents > 0)
        require(maxVirtualDuration.isFinite() && maxVirtualDuration > Duration.ZERO)
    }
}

sealed interface QueueScheduleResult {
    data class Scheduled(val id: ScheduledEventId) : QueueScheduleResult
    data object Full : QueueScheduleResult
    data object SequenceExhausted : QueueScheduleResult
}
