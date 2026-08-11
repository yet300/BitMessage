package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import kotlin.jvm.JvmInline

@JvmInline
value class FragmentId private constructor(
    val value: Bytes,
) {
    companion object {
        const val BYTE_SIZE: Int = 8

        fun of(value: Bytes): FragmentId =
            FragmentId(Bytes.requireExactSize(value.copyToByteArray(), BYTE_SIZE))
    }
}

data class FragmentPayload(
    val id: FragmentId,
    val index: UShort,
    val total: UShort,
    val originalType: PacketType,
    val data: Bytes,
) {
    init {
        require(total > 0.toUShort()) { "Fragment total must be positive." }
        require(index < total) { "Fragment index must be less than total." }
    }
}

object FragmentPayloadCodec {
    const val HEADER_BYTES: Int = 13

    fun decode(payload: Bytes): DecodeResult<FragmentPayload> {
        if (payload.size < HEADER_BYTES) return DecodeResult.Failure(DecodeError.TRUNCATED)

        val bytes = payload.copyToByteArray()
        val index = readUShort(bytes, INDEX_OFFSET)
        val total = readUShort(bytes, TOTAL_OFFSET)
        if (total == 0.toUShort() || index >= total) {
            return DecodeResult.Failure(DecodeError.MALFORMED_FIELD)
        }

        return DecodeResult.Success(
            FragmentPayload(
                id = FragmentId.of(Bytes.copyOf(bytes.copyOfRange(0, FragmentId.BYTE_SIZE))),
                index = index,
                total = total,
                originalType = PacketType.of(bytes[TYPE_OFFSET].toUByte()),
                data = Bytes.copyOf(bytes.copyOfRange(HEADER_BYTES, bytes.size)),
            ),
        )
    }

    fun encode(fragment: FragmentPayload): EncodeResult {
        val data = fragment.data.copyToByteArray()
        val output = ByteArray(HEADER_BYTES + data.size)
        fragment.id.value.copyToByteArray().copyInto(output)
        writeUShort(output, INDEX_OFFSET, fragment.index)
        writeUShort(output, TOTAL_OFFSET, fragment.total)
        output[TYPE_OFFSET] = fragment.originalType.value.toByte()
        data.copyInto(output, destinationOffset = HEADER_BYTES)
        return EncodeResult.Success(Bytes.copyOf(output))
    }

    private fun readUShort(bytes: ByteArray, offset: Int): UShort =
        (((bytes[offset].toInt() and 0xff) shl Byte.SIZE_BITS) or
            (bytes[offset + 1].toInt() and 0xff)).toUShort()

    private fun writeUShort(bytes: ByteArray, offset: Int, value: UShort) {
        bytes[offset] = (value.toInt() ushr Byte.SIZE_BITS).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private const val INDEX_OFFSET: Int = 8
    private const val TOTAL_OFFSET: Int = 10
    private const val TYPE_OFFSET: Int = 12
}
