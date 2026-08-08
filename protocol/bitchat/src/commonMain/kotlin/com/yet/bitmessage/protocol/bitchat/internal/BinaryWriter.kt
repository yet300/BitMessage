package com.yet.bitmessage.protocol.bitchat.internal

import com.yet.bitmessage.foundation.Bytes

internal class BinaryWriter(
    private val maxSize: Int,
) {
    private val buffer = ArrayList<Byte>()

    init {
        require(maxSize >= 0) { "Maximum binary size must not be negative." }
    }

    fun writeUByte(value: UByte): BinaryWriteResult = writeBytes(byteArrayOf(value.toByte()))

    fun writeUShortBigEndian(value: UShort): BinaryWriteResult =
        writeBytes(
            byteArrayOf(
                (value.toUInt() shr 8).toByte(),
                value.toByte(),
            ),
        )

    fun writeUIntBigEndian(value: UInt): BinaryWriteResult =
        writeBytes(
            byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte(),
            ),
        )

    fun writeULongBigEndian(value: ULong): BinaryWriteResult =
        writeBytes(
            ByteArray(8) { index ->
                (value shr ((7 - index) * 8)).toByte()
            },
        )

    fun writeExact(value: Bytes): BinaryWriteResult = writeBytes(value.copyToByteArray())

    fun toBytes(): Bytes = Bytes.copyOf(buffer.toByteArray())

    private fun writeBytes(bytes: ByteArray): BinaryWriteResult {
        if (bytes.size > maxSize - buffer.size) {
            return BinaryWriteResult.Failure(BinaryWriteError.CAPACITY_EXCEEDED)
        }
        bytes.forEach(buffer::add)
        return BinaryWriteResult.Success
    }
}

internal sealed interface BinaryWriteResult {
    data object Success : BinaryWriteResult

    data class Failure(val error: BinaryWriteError) : BinaryWriteResult
}

internal enum class BinaryWriteError {
    CAPACITY_EXCEEDED,
}
