package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.model.LinkId
import kotlin.time.Duration

data class SimulatedEndpoint(
    val nodeId: SimulatedNodeId,
    val linkId: LinkId,
)

sealed interface DirectedWriteDecision {
    data object Accepted : DirectedWriteDecision
    data object Backpressured : DirectedWriteDecision
    data object Disconnected : DirectedWriteDecision
    data class PayloadTooLarge(val maximumBytes: Int) : DirectedWriteDecision
}

data class DirectedSimulatedLink(
    val id: SimulatedLinkId,
    val source: SimulatedEndpoint,
    val target: SimulatedEndpoint,
    val mtu: Int,
    val latency: Duration,
    val open: Boolean = true,
    val writeReady: Boolean = true,
    val epoch: Long = 0,
) {
    init {
        require(source.nodeId != target.nodeId) { "A directed link must connect distinct nodes." }
        require(mtu > 0) { "Link MTU must be positive." }
        require(latency.isFinite() && latency >= Duration.ZERO) {
            "Link latency must be finite and nonnegative."
        }
        require(epoch >= 0) { "Link epoch must be nonnegative." }
    }

    fun decide(byteCount: Int): DirectedWriteDecision {
        require(byteCount >= 0) { "Byte count must be nonnegative." }
        return when {
            !open -> DirectedWriteDecision.Disconnected
            !writeReady -> DirectedWriteDecision.Backpressured
            byteCount > mtu -> DirectedWriteDecision.PayloadTooLarge(mtu)
            else -> DirectedWriteDecision.Accepted
        }
    }
}

@ConsistentCopyVisibility
data class SimulatedConnection private constructor(
    val id: String,
    val aToB: DirectedSimulatedLink,
    val bToA: DirectedSimulatedLink,
) {
    companion object {
        fun create(
            id: String,
            first: SimulatedNodeId,
            second: SimulatedNodeId,
            mtu: Int,
            latency: Duration,
        ): SimulatedConnection {
            require(id.isNotBlank()) { "Connection ID must not be blank." }
            require(id == id.trim()) { "Connection ID must not have surrounding whitespace." }
            require(first != second) { "A connection must join distinct nodes." }
            val firstEndpoint = SimulatedEndpoint(first, LinkId.of("$id:a"))
            val secondEndpoint = SimulatedEndpoint(second, LinkId.of("$id:b"))
            return SimulatedConnection(
                id = id,
                aToB = DirectedSimulatedLink(
                    id = SimulatedLinkId.of("$id:a-to-b"),
                    source = firstEndpoint,
                    target = secondEndpoint,
                    mtu = mtu,
                    latency = latency,
                ),
                bToA = DirectedSimulatedLink(
                    id = SimulatedLinkId.of("$id:b-to-a"),
                    source = secondEndpoint,
                    target = firstEndpoint,
                    mtu = mtu,
                    latency = latency,
                ),
            )
        }
    }
}
