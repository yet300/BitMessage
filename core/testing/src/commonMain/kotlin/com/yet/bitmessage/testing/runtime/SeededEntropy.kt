package com.yet.bitmessage.testing.runtime

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.EntropyGenerated
import com.yet.bitmessage.foundation.EntropyRequest
import com.yet.bitmessage.foundation.EntropySource
import kotlin.random.Random

/**
 * Deterministic test-only entropy. It is not cryptographic and must not be used by production code.
 */
class SeededEntropy(
    seed: Int,
) : EntropySource {
    private val random = Random(seed)

    override fun generate(request: EntropyRequest): EntropyGenerated {
        val generated = ByteArray(request.byteCount)
        random.nextBytes(generated)
        return EntropyGenerated(request.correlationId, Bytes.copyOf(generated))
    }
}
