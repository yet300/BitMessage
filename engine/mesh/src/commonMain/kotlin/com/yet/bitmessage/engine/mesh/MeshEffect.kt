package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.PacketIdentityInput
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.transport.api.LinkCommand
import kotlin.time.Duration

sealed interface MeshEffect {
    val correlationId: CorrelationId
    val generation: Generation

    data class DecodePacket(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val source: PacketSource,
        val bytes: Bytes,
    ) : MeshEffect

    data class ComputePacketDigest(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val input: PacketIdentityInput,
    ) : MeshEffect

    data class VerifySignature(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val sender: WirePeerId,
        val transcript: Bytes,
        val signature: Bytes,
    ) : MeshEffect

    data class DecodeFragmentPayload(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val packetId: PacketId,
        val source: PacketSource,
        val sender: WirePeerId,
        val payload: Bytes,
    ) : MeshEffect

    data class EncodeRelay(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val packetId: PacketId,
        val packet: DecodedPacket,
        val outgoingTtl: UByte,
    ) : MeshEffect

    data class RequestEntropy(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val packetId: PacketId,
        val source: PacketSource,
        val packet: DecodedPacket,
        val outgoingTtl: UByte,
        val byteCount: Int = RELAY_ENTROPY_BYTES,
    ) : MeshEffect {
        init {
            require(byteCount == RELAY_ENTROPY_BYTES) { "Relay entropy requests exactly two bytes." }
        }
    }

    data class Schedule(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val timerId: TimerId,
        val delay: Duration,
    ) : MeshEffect {
        init {
            require(delay.isFinite() && delay >= Duration.ZERO) {
                "Schedule delay must be finite and nonnegative."
            }
        }
    }

    data class Cancel(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val timerId: TimerId,
    ) : MeshEffect

    data class WriteLink(
        val command: LinkCommand.Write,
    ) : MeshEffect {
        override val correlationId: CorrelationId
            get() = command.correlationId
        override val generation: Generation
            get() = command.generation
    }

    data class CloseLink(
        val command: LinkCommand.Close,
    ) : MeshEffect {
        override val correlationId: CorrelationId
            get() = command.correlationId
        override val generation: Generation
            get() = command.generation
    }

    data class PublishPublicPayload(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        val packetId: PacketId,
        val sender: WirePeerId,
        val ingressLink: LinkId,
        val timestamp: ULong,
        val payload: Bytes,
    ) : MeshEffect

    private companion object {
        const val RELAY_ENTROPY_BYTES: Int = 2
    }
}
