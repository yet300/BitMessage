package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime

/** An explicit, precompiled input dispatched by the global virtual-event queue. */
sealed interface ScenarioAction {
    val at: MonotonicTime

    data class Inject(
        override val at: MonotonicTime,
        val directionId: SimulatedLinkId,
        val wire: Bytes,
    ) : ScenarioAction {
        override fun toString(): String = "Inject(at=$at, directionId=$directionId, byteCount=${wire.size})"
    }

    data class Stop(override val at: MonotonicTime, val nodeId: SimulatedNodeId) : ScenarioAction
    data class Start(override val at: MonotonicTime, val nodeId: SimulatedNodeId) : ScenarioAction
}
