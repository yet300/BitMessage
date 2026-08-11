package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.DecodedPacket
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.FragmentPayload
import com.yet.bitmessage.protocol.bitchat.PacketId
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkResult

enum class MeshFailureCode {
    DECODE_FAILED,
    DIGEST_FAILED,
    VERIFICATION_FAILED,
    ENCODING_FAILED,
    TIMER_FAILED,
    WRITE_FAILED,
    EFFECT_EXECUTION_FAILED,
}

sealed interface MeshResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MeshResult<T>

    data class Failure(
        val code: MeshFailureCode,
    ) : MeshResult<Nothing>
}

sealed interface MeshEvent {
    val generation: Generation
    val observedAt: MonotonicTime

    data class LinkObserved(
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val event: LinkEvent,
    ) : MeshEvent

    data class PacketDecoded(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val source: PacketSource,
        val result: DecodeResult<DecodedPacket>,
    ) : MeshAsyncEvent

    data class PacketDigestComputed(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val result: MeshResult<Bytes>,
    ) : MeshAsyncEvent

    data class SignatureVerified(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val result: MeshResult<Boolean>,
    ) : MeshAsyncEvent

    data class FragmentPayloadDecoded(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val packetId: PacketId,
        val source: PacketSource,
        val sender: WirePeerId,
        val result: DecodeResult<FragmentPayload>,
    ) : MeshAsyncEvent

    data class RelayEncoded(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val packetId: PacketId,
        val result: EncodeResult,
    ) : MeshAsyncEvent

    data class EntropyProvided(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val packetId: PacketId,
        val source: PacketSource,
        val packet: DecodedPacket,
        val outgoingTtl: UByte,
        val result: MeshResult<Bytes>,
    ) : MeshAsyncEvent

    data class TimerElapsed(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val timerId: TimerId,
    ) : MeshAsyncEvent

    data class LinkCompleted(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val result: LinkResult,
    ) : MeshAsyncEvent {
        init {
            require(result.correlationId == correlationId) { "Link result correlation must match its event." }
            require(result.generation == generation) { "Link result generation must match its event." }
        }
    }

    data class EffectFailed(
        override val correlationId: CorrelationId,
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val code: MeshFailureCode,
    ) : MeshAsyncEvent

    data class RuntimeStarted(
        override val generation: Generation,
        override val observedAt: MonotonicTime,
        val localPeer: WirePeerId,
    ) : MeshEvent

    data class RuntimeStopping(
        override val generation: Generation,
        override val observedAt: MonotonicTime,
    ) : MeshEvent
}

sealed interface MeshAsyncEvent : MeshEvent {
    val correlationId: CorrelationId
}
