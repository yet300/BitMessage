package com.yet.bitmessage.engine.mesh.runtime

import com.yet.bitmessage.engine.mesh.MeshEvent
import com.yet.bitmessage.engine.mesh.PacketSource
import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.protocol.bitchat.BitchatCodec
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.SigningTranscript

sealed interface PacketIngress {
    data class Accepted(
        val event: MeshEvent.PacketDecoded,
    ) : PacketIngress

    data class Rejected(
        val error: DecodeError,
    ) : PacketIngress
}

object MeshProtocolAdapter {
    fun decode(
        generation: Generation,
        observedAt: MonotonicTime,
        source: PacketSource,
        bytes: Bytes,
    ): PacketIngress {
        val packet = when (val decoded = BitchatCodec.decode(bytes)) {
            is DecodeResult.Success -> decoded.value
            is DecodeResult.Failure -> return PacketIngress.Rejected(decoded.error)
        }
        val signingTranscript = if (packet.signature == null) {
            null
        } else {
            when (val transcript = SigningTranscript.build(packet)) {
                is DecodeResult.Success -> transcript.value
                is DecodeResult.Failure -> return PacketIngress.Rejected(transcript.error)
            }
        }
        return PacketIngress.Accepted(
            MeshEvent.PacketDecoded(
                generation = generation,
                observedAt = observedAt,
                source = source,
                packet = packet,
                signingTranscript = signingTranscript,
            ),
        )
    }
}
