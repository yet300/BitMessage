package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.transport.api.LinkFailureCode
import kotlin.time.Duration

data class TransmissionSelector(
    val directionId: SimulatedLinkId,
    val writeOrdinal: Long,
) {
    init {
        require(writeOrdinal > 0) { "Write ordinal must be positive." }
    }
}

sealed interface TransmissionFault {
    val selector: TransmissionSelector

    data class Drop(override val selector: TransmissionSelector) : TransmissionFault

    data class Duplicate(
        override val selector: TransmissionSelector,
        val copies: Int,
    ) : TransmissionFault {
        init {
            require(copies in 2..8) { "Duplicate copies must be in 2..8." }
        }
    }

    data class AddDelay(
        override val selector: TransmissionSelector,
        val delay: Duration,
    ) : TransmissionFault {
        init {
            require(delay.isFinite() && delay >= Duration.ZERO) {
                "Additional delay must be finite and nonnegative."
            }
        }
    }

    data class CompleteWith(
        override val selector: TransmissionSelector,
        val result: PlannedLinkResult,
    ) : TransmissionFault

    data class CorruptByte(
        override val selector: TransmissionSelector,
        val offset: Int,
        val xorMask: UByte,
    ) : TransmissionFault {
        init {
            require(offset >= 0) { "Corruption byte offset must be nonnegative." }
            require(xorMask != 0.toUByte()) { "Corruption XOR mask must change at least one bit." }
        }
    }
}

data class TimedLinkFault(
    val id: String,
    val deadline: MonotonicTime,
    val action: LinkFaultAction,
) {
    init {
        require(id.isNotBlank()) { "Timed link fault ID must not be blank." }
    }
}

sealed interface LinkFaultAction {
    data class SetReadiness(val directionId: SimulatedLinkId, val ready: Boolean) : LinkFaultAction
    data class SetOpen(val directionId: SimulatedLinkId, val open: Boolean) : LinkFaultAction

    data class Partition(
        val directionIds: List<SimulatedLinkId>,
        val active: Boolean,
    ) : LinkFaultAction {
        init {
            require(directionIds.isNotEmpty()) { "A partition must select at least one direction." }
            require(directionIds.size == directionIds.toSet().size) {
                "A partition must not repeat a direction."
            }
        }
    }

    data class Reconnect(val connectionId: String) : LinkFaultAction {
        init {
            require(connectionId.isNotBlank()) { "Reconnect connection ID must not be blank." }
        }
    }
}

sealed interface PlannedLinkResult {
    data object Backpressured : PlannedLinkResult
    data object Disconnected : PlannedLinkResult
    data object Unsupported : PlannedLinkResult
    data class Failed(val code: LinkFailureCode) : PlannedLinkResult
}

/** An immutable, finite set of explicit decisions. Execution never consults an RNG. */
class FaultPlan(
    transmissionFaults: List<TransmissionFault> = emptyList(),
    timedLinkFaults: List<TimedLinkFault> = emptyList(),
    val maximumActions: Int = SimulationLimits().maxFaultActions,
) {
    private val transmissions: List<TransmissionFault>
    private val timed: List<TimedLinkFault>
    private val bySelector: Map<TransmissionSelector, TransmissionFault>
    private val byTimedId: Map<String, TimedLinkFault>

    init {
        require(maximumActions > 0) { "Fault action limit must be positive." }
        require(timedLinkFaults.size <= maximumActions &&
            transmissionFaults.size <= maximumActions - timedLinkFaults.size) {
            "Fault plan exceeds maximum action count."
        }
        transmissions = transmissionFaults.toList()
        timed = timedLinkFaults.map(TimedLinkFault::defensiveCopy)
        bySelector = transmissions.associateBy { it.selector }
        byTimedId = timed.associateBy { it.id }
        require(bySelector.size == transmissions.size) { "Transmission selectors must be distinct." }
        require(byTimedId.size == timed.size) { "Timed fault IDs must be distinct." }
    }

    val transmissionFaults: List<TransmissionFault>
        get() = transmissions.toList()

    val timedLinkFaults: List<TimedLinkFault>
        get() = timed.map(TimedLinkFault::defensiveCopy)

    fun timedFault(id: String): TimedLinkFault? = byTimedId[id]?.defensiveCopy()

    fun decision(directionId: SimulatedLinkId, writeOrdinal: Long): TransmissionFault? {
        require(writeOrdinal > 0) { "Write ordinal must be positive." }
        return bySelector[TransmissionSelector(directionId, writeOrdinal)]
    }

    override fun equals(other: Any?): Boolean = other is FaultPlan &&
        transmissions == other.transmissions && timed == other.timed && maximumActions == other.maximumActions

    override fun hashCode(): Int = 31 * (31 * transmissions.hashCode() + timed.hashCode()) + maximumActions

    override fun toString(): String =
        "FaultPlan(transmissionFaults=$transmissions, timedLinkFaults=$timed, maximumActions=$maximumActions)"
}

private fun TimedLinkFault.defensiveCopy(): TimedLinkFault = when (val action = action) {
    is LinkFaultAction.Partition -> copy(action = action.copy(directionIds = action.directionIds.toList()))
    is LinkFaultAction.SetReadiness,
    is LinkFaultAction.SetOpen,
    is LinkFaultAction.Reconnect,
    -> this
}
