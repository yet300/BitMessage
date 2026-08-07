package com.yet.bitmessage.foundation

data class EntropyRequest(
    val correlationId: CorrelationId,
    val byteCount: Int,
) {
    init {
        require(byteCount >= 0) { "Entropy byte count must not be negative." }
    }
}

data class EntropyGenerated(
    val correlationId: CorrelationId,
    val bytes: Bytes,
)

fun interface EntropySource {
    fun generate(request: EntropyRequest): EntropyGenerated
}
