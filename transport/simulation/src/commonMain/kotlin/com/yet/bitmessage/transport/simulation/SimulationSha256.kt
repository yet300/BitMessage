package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes

internal object SimulationSha256 {
    fun digest(input: Bytes): Bytes {
        val inputBytes = input.copyToByteArray()
        val bitLength = inputBytes.size.toULong() * Byte.SIZE_BITS.toULong()
        val zeroPaddingBytes = (BLOCK_BYTES - ((inputBytes.size + 1 + LENGTH_BYTES) % BLOCK_BYTES)) % BLOCK_BYTES
        val padded = ByteArray(inputBytes.size + 1 + zeroPaddingBytes + LENGTH_BYTES)
        inputBytes.copyInto(padded)
        padded[inputBytes.size] = 0x80.toByte()
        repeat(LENGTH_BYTES) { index ->
            val shift = (LENGTH_BYTES - index - 1) * Byte.SIZE_BITS
            padded[padded.size - LENGTH_BYTES + index] = (bitLength shr shift).toByte()
        }

        val hash = INITIAL_HASH.copyOf()
        val words = IntArray(ROUND_COUNT)
        var blockOffset = 0
        while (blockOffset < padded.size) {
            repeat(BLOCK_WORDS) { index ->
                val offset = blockOffset + index * Int.SIZE_BYTES
                words[index] =
                    ((padded[offset].toInt() and 0xff) shl 24) or
                        ((padded[offset + 1].toInt() and 0xff) shl 16) or
                        ((padded[offset + 2].toInt() and 0xff) shl 8) or
                        (padded[offset + 3].toInt() and 0xff)
            }
            for (index in BLOCK_WORDS until ROUND_COUNT) {
                val smallSigma0 =
                    rotateRight(words[index - 15], 7) xor
                        rotateRight(words[index - 15], 18) xor
                        (words[index - 15] ushr 3)
                val smallSigma1 =
                    rotateRight(words[index - 2], 17) xor
                        rotateRight(words[index - 2], 19) xor
                        (words[index - 2] ushr 10)
                words[index] = words[index - 16] + smallSigma0 + words[index - 7] + smallSigma1
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]

            repeat(ROUND_COUNT) { index ->
                val bigSigma1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
                val choose = (e and f) xor (e.inv() and g)
                val temporary1 = h + bigSigma1 + choose + ROUND_CONSTANTS[index] + words[index]
                val bigSigma0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temporary2 = bigSigma0 + majority

                h = g
                g = f
                f = e
                e = d + temporary1
                d = c
                c = b
                b = a
                a = temporary1 + temporary2
            }

            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
            blockOffset += BLOCK_BYTES
        }

        val digest = ByteArray(DIGEST_BYTES)
        hash.forEachIndexed { index, word ->
            val offset = index * Int.SIZE_BYTES
            digest[offset] = (word ushr 24).toByte()
            digest[offset + 1] = (word ushr 16).toByte()
            digest[offset + 2] = (word ushr 8).toByte()
            digest[offset + 3] = word.toByte()
        }
        return Bytes.copyOf(digest)
    }

    private fun rotateRight(value: Int, bitCount: Int): Int =
        (value ushr bitCount) or (value shl (Int.SIZE_BITS - bitCount))

    private const val BLOCK_BYTES: Int = 64
    private const val BLOCK_WORDS: Int = 16
    private const val LENGTH_BYTES: Int = ULong.SIZE_BYTES
    private const val ROUND_COUNT: Int = 64
    private const val DIGEST_BYTES: Int = 32

    private val INITIAL_HASH = intArrayOf(
        0x6a09e667u.toInt(),
        0xbb67ae85u.toInt(),
        0x3c6ef372u.toInt(),
        0xa54ff53au.toInt(),
        0x510e527fu.toInt(),
        0x9b05688cu.toInt(),
        0x1f83d9abu.toInt(),
        0x5be0cd19u.toInt(),
    )

    private val ROUND_CONSTANTS = intArrayOf(
        0x428a2f98u.toInt(), 0x71374491u.toInt(), 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
        0x3956c25bu.toInt(), 0x59f111f1u.toInt(), 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01u.toInt(), 0x243185beu.toInt(), 0x550c7dc3u.toInt(),
        0x72be5d74u.toInt(), 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6u.toInt(), 0x240ca1ccu.toInt(),
        0x2de92c6fu.toInt(), 0x4a7484aau.toInt(), 0x5cb0a9dcu.toInt(), 0x76f988dau.toInt(),
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
        0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351u.toInt(), 0x14292967u.toInt(),
        0x27b70a85u.toInt(), 0x2e1b2138u.toInt(), 0x4d2c6dfcu.toInt(), 0x53380d13u.toInt(),
        0x650a7354u.toInt(), 0x766a0abbu.toInt(), 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
        0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070u.toInt(),
        0x19a4c116u.toInt(), 0x1e376c08u.toInt(), 0x2748774cu.toInt(), 0x34b0bcb5u.toInt(),
        0x391c0cb3u.toInt(), 0x4ed8aa4au.toInt(), 0x5b9cca4fu.toInt(), 0x682e6ff3u.toInt(),
        0x748f82eeu.toInt(), 0x78a5636fu.toInt(), 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
        0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
    )
}
