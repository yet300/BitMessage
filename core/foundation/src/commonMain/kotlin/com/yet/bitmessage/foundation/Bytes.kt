package com.yet.bitmessage.foundation

class Bytes private constructor(
    private val storage: ByteArray,
) {

    val size: Int
        get() = storage.size

    operator fun get(index: Int): UByte = storage[index].toUByte()

    fun copyToByteArray(): ByteArray = storage.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Bytes && storage.contentEquals(other.storage)

    override fun hashCode(): Int = storage.contentHashCode()

    override fun toString(): String = "Bytes(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): Bytes = Bytes(bytes.copyOf())

        fun requireExactSize(bytes: ByteArray, expectedSize: Int): Bytes {
            require(expectedSize >= 0) { "Expected size must not be negative." }
            require(bytes.size == expectedSize) {
                "Expected $expectedSize bytes, but received ${bytes.size}."
            }
            return Bytes(bytes.copyOf())
        }
    }
}
