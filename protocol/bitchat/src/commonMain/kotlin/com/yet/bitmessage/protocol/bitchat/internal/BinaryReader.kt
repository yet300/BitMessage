package com.yet.bitmessage.protocol.bitchat.internal

import com.yet.bitmessage.foundation.Bytes

internal class BinaryReader(
    private val source: Bytes,
) {
    private var cursor: Int = 0

    val remaining: Int
        get() = source.size - cursor

    fun readUByte(): BinaryReadResult<UByte> {
        if (!hasRemaining(1)) return BinaryReadResult.Failure(BinaryReadError.TRUNCATED)
        return BinaryReadResult.Success(source[cursor++])
    }

    fun readUShortBigEndian(): BinaryReadResult<UShort> {
        if (!hasRemaining(U_SHORT_BYTES)) return BinaryReadResult.Failure(BinaryReadError.TRUNCATED)
        val result = ((source[cursor].toUInt() shl 8) or source[cursor + 1].toUInt()).toUShort()
        cursor += U_SHORT_BYTES
        return BinaryReadResult.Success(result)
    }

    fun readUIntBigEndian(): BinaryReadResult<UInt> {
        if (!hasRemaining(U_INT_BYTES)) return BinaryReadResult.Failure(BinaryReadError.TRUNCATED)
        val result =
            (source[cursor].toUInt() shl 24) or
                (source[cursor + 1].toUInt() shl 16) or
                (source[cursor + 2].toUInt() shl 8) or
                source[cursor + 3].toUInt()
        cursor += U_INT_BYTES
        return BinaryReadResult.Success(result)
    }

    fun readULongBigEndian(): BinaryReadResult<ULong> {
        if (!hasRemaining(U_LONG_BYTES)) return BinaryReadResult.Failure(BinaryReadError.TRUNCATED)
        var result = 0uL
        repeat(U_LONG_BYTES) { index ->
            result = (result shl 8) or source[cursor + index].toULong()
        }
        cursor += U_LONG_BYTES
        return BinaryReadResult.Success(result)
    }

    fun readExact(count: Int): BinaryReadResult<Bytes> {
        if (count < 0) return BinaryReadResult.Failure(BinaryReadError.INVALID_LENGTH)
        if (!hasRemaining(count)) return BinaryReadResult.Failure(BinaryReadError.TRUNCATED)

        val result = ByteArray(count) { index -> source[cursor + index].toByte() }
        cursor += count
        return BinaryReadResult.Success(Bytes.copyOf(result))
    }

    private fun hasRemaining(count: Int): Boolean = count <= remaining

    private companion object {
        const val U_SHORT_BYTES = 2
        const val U_INT_BYTES = 4
        const val U_LONG_BYTES = 8
    }
}

internal sealed interface BinaryReadResult<out T> {
    data class Success<T>(val value: T) : BinaryReadResult<T>

    data class Failure(val error: BinaryReadError) : BinaryReadResult<Nothing>
}

internal enum class BinaryReadError {
    TRUNCATED,
    INVALID_LENGTH,
}
