package com.yet.bitmessage.protocol.bitchat.internal

import com.yet.bitmessage.foundation.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals

class BinaryReaderWriterTest {
    @Test
    fun readerAdvancesItsCursorAndUsesBigEndianIntegers() {
        val reader = BinaryReader(Bytes.copyOf(byteArrayOf(1, 0x02, 0x03, 0x04, 0x05)))

        assertEquals(BinaryReadResult.Success(1u.toUByte()), reader.readUByte())
        assertEquals(BinaryReadResult.Success(0x0203u.toUShort()), reader.readUShortBigEndian())
        assertEquals(BinaryReadResult.Success(Bytes.copyOf(byteArrayOf(0x04, 0x05))), reader.readExact(2))
        assertEquals(0, reader.remaining)
    }

    @Test
    fun readerReportsTruncationWithoutAllocatingAnAdvertisedLength() {
        val reader = BinaryReader(Bytes.copyOf(byteArrayOf(0x00, 0x04, 0x41)))

        assertEquals(BinaryReadResult.Failure(BinaryReadError.TRUNCATED), reader.readExact(4))
        assertEquals(3, reader.remaining)
    }

    @Test
    fun writerHasExplicitCapacityFailureAndBigEndianOutput() {
        val writer = BinaryWriter(maxSize = 3)

        assertEquals(BinaryWriteResult.Success, writer.writeUShortBigEndian(0x0102u))
        assertEquals(BinaryWriteResult.Failure(BinaryWriteError.CAPACITY_EXCEEDED), writer.writeExact(Bytes.copyOf(byteArrayOf(3, 4))))
        assertEquals(Bytes.copyOf(byteArrayOf(1, 2)), writer.toBytes())
    }

    @Test
    fun writerReturnsAnOwnedBytesValue() {
        val writer = BinaryWriter(maxSize = 4)
        writer.writeUShortBigEndian(0x0102u)
        val firstResult = writer.toBytes()
        writer.writeUByte(0x03u)

        assertEquals(Bytes.copyOf(byteArrayOf(1, 2)), firstResult)
        assertEquals(Bytes.copyOf(byteArrayOf(1, 2, 3)), writer.toBytes())
    }

    @Test
    fun readerReturnsTypedValuesForLargerBigEndianIntegers() {
        val reader = BinaryReader(
            Bytes.copyOf(
                byteArrayOf(
                    0x01, 0x02, 0x03, 0x04,
                    0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c,
                ),
            ),
        )

        assertEquals(BinaryReadResult.Success(0x01020304u), reader.readUIntBigEndian())
        assertEquals(BinaryReadResult.Success(0x05060708090a0b0cuL), reader.readULongBigEndian())
    }
}
