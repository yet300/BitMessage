package com.yet.bitmessage.protocol.bitchat.payload

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.DecodeError
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.protocol.bitchat.WirePeerId
import com.yet.bitmessage.protocol.bitchat.internal.BinaryReadResult
import com.yet.bitmessage.protocol.bitchat.internal.BinaryReader

object AnnouncementCodec {
    fun decode(payload: Bytes): DecodeResult<AnnouncementPayload> {
        if (payload.size == 0) return DecodeResult.Failure(DecodeError.EMPTY_INPUT)

        val reader = BinaryReader(payload)
        val tlvs = ArrayList<AnnouncementTlv>()
        val seenTypes = HashSet<UByte>()
        var nickname: Bytes? = null
        var noisePublicKey: Bytes? = null
        var signingPublicKey: Bytes? = null
        var directNeighbors: List<WirePeerId> = emptyList()
        var capabilities: CapabilityBits? = null

        while (reader.remaining > 0) {
            val type = reader.readUByte().decodeOrNull() ?: return truncated()
            val length = reader.readUByte().decodeOrNull()?.toInt() ?: return truncated()
            val value = reader.readExact(length).decodeOrNull() ?: return DecodeResult.Failure(DecodeError.INVALID_LENGTH)
            if (!seenTypes.add(type)) return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)

            val tlv = AnnouncementTlv(type, value)
            tlvs += tlv
            when (type) {
                NICKNAME_TYPE -> nickname = value
                NOISE_PUBLIC_KEY_TYPE -> {
                    if (value.size != PUBLIC_KEY_BYTES) return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
                    noisePublicKey = value
                }
                SIGNING_PUBLIC_KEY_TYPE -> {
                    if (value.size != PUBLIC_KEY_BYTES) return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
                    signingPublicKey = value
                }
                DIRECT_NEIGHBOR_TYPE -> {
                    if (value.size != WirePeerId.BYTE_SIZE) return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
                    directNeighbors = listOf(WirePeerId.of(value))
                }
                CAPABILITIES_TYPE -> {
                    if (value.size !in 1..CAPABILITY_BYTES) return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
                    capabilities = CapabilityBits.fromWire(value)
                }
            }
        }

        return if (nickname == null || noisePublicKey == null || signingPublicKey == null) {
            DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
        } else {
            DecodeResult.Success(
                AnnouncementPayload(
                    nickname = nickname,
                    noisePublicKey = noisePublicKey,
                    signingPublicKey = signingPublicKey,
                    directNeighbors = directNeighbors,
                    capabilities = capabilities,
                    tlvs = tlvs,
                    rawPayload = payload,
                ),
            )
        }
    }

    fun encode(payload: AnnouncementPayload): EncodeResult = EncodeResult.Success(payload.rawPayload)

    private fun <T> BinaryReadResult<T>.decodeOrNull(): T? =
        (this as? BinaryReadResult.Success<T>)?.value

    private fun truncated(): DecodeResult.Failure = DecodeResult.Failure(DecodeError.TRUNCATED)

    private const val NICKNAME_TYPE: UByte = 0x01u
    private const val NOISE_PUBLIC_KEY_TYPE: UByte = 0x02u
    private const val SIGNING_PUBLIC_KEY_TYPE: UByte = 0x03u
    private const val DIRECT_NEIGHBOR_TYPE: UByte = 0x04u
    private const val CAPABILITIES_TYPE: UByte = 0x05u
    private const val PUBLIC_KEY_BYTES = 32
    private const val CAPABILITY_BYTES = 8
}
