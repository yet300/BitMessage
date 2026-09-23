package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.runtime.MeshTimerKey
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.transport.api.LinkEvent

enum class ScheduledCategory(val isPacketNetworkWork: Boolean) {
    PAYLOAD_DELIVERY(true),
    MESH_EVENT(true),
    LINK_OBSERVATION(false),
    RUNTIME_TIMER(false),
    LINK_FAULT(false),
}

internal sealed interface SimulationEvent {
    val category: ScheduledCategory

    data class DeliverPayload(
        val directionId: SimulatedLinkId,
        val targetNode: SimulatedNodeId,
        val targetLinkId: LinkId,
        val bytes: Bytes,
    ) : SimulationEvent {
        override val category: ScheduledCategory = ScheduledCategory.PAYLOAD_DELIVERY
    }

    data class DeliverMeshEvent(
        val targetNode: SimulatedNodeId,
        val event: MeshEvent,
    ) : SimulationEvent {
        override val category: ScheduledCategory = ScheduledCategory.MESH_EVENT
    }

    data class ObserveLink(
        val targetNode: SimulatedNodeId,
        val event: LinkEvent,
    ) : SimulationEvent {
        init {
            require(event !is LinkEvent.PayloadReceived) {
                "Payloads must pass through protocol decoding before entering the mesh runtime."
            }
        }

        override val category: ScheduledCategory = ScheduledCategory.LINK_OBSERVATION
    }

    data class FireRuntimeTimer(
        val targetNode: SimulatedNodeId,
        val key: MeshTimerKey,
    ) : SimulationEvent {
        override val category: ScheduledCategory = ScheduledCategory.RUNTIME_TIMER
    }

    data class ApplyLinkFault(val actionId: String) : SimulationEvent {
        override val category: ScheduledCategory = ScheduledCategory.LINK_FAULT
    }
}
