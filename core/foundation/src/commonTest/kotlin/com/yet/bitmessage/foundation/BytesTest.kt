package com.yet.bitmessage.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BytesTest {

    @Test
    fun copiesAtBothOwnershipBoundaries() {
        val input = byteArrayOf(1, 2)
        val bytes = Bytes.copyOf(input)

        input[0] = 9
        assertEquals(1.toUByte(), bytes[0])

        val output = bytes.copyToByteArray()
        output[1] = 8
        assertEquals(2.toUByte(), bytes[1])
    }

    @Test
    fun hasContentBasedEqualityAndHashCode() {
        val first = Bytes.copyOf(byteArrayOf(1, 2))
        val sameContent = Bytes.copyOf(byteArrayOf(1, 2))
        val differentContent = Bytes.copyOf(byteArrayOf(2, 1))

        assertEquals(first, sameContent)
        assertEquals(first.hashCode(), sameContent.hashCode())
        assertNotEquals(first, differentContent)
    }

    @Test
    fun supportsArbitrarySizesAndUnsignedIndexedAccess() {
        val empty = Bytes.copyOf(byteArrayOf())
        val unsigned = Bytes.copyOf(byteArrayOf(-1, -128))

        assertEquals(0, empty.size)
        assertEquals(255.toUByte(), unsigned[0])
        assertEquals(128.toUByte(), unsigned[1])
    }

    @Test
    fun exactSizeRejectsInvalidExpectedSizeAndMismatchesWithoutResizing() {
        assertFailsWith<IllegalArgumentException> { Bytes.requireExactSize(byteArrayOf(), -1) }
        assertFailsWith<IllegalArgumentException> { Bytes.requireExactSize(byteArrayOf(1), 2) }
        assertFailsWith<IllegalArgumentException> { Bytes.requireExactSize(byteArrayOf(1, 2, 3), 2) }

        assertEquals(Bytes.copyOf(byteArrayOf(1, 2)), Bytes.requireExactSize(byteArrayOf(1, 2), 2))
    }

    @Test
    fun exactSizeCopiesAtBothOwnershipBoundaries() {
        val input = byteArrayOf(1, 2)
        val bytes = Bytes.requireExactSize(input, 2)

        input[0] = 9
        assertEquals(1.toUByte(), bytes[0])

        val output = bytes.copyToByteArray()
        output[1] = 8
        assertEquals(2.toUByte(), bytes[1])
    }

    @Test
    fun toStringReportsOnlySize() {
        val bytes = Bytes.copyOf(byteArrayOf(42, -1))

        assertEquals("Bytes(size=2)", bytes.toString())
    }
}
