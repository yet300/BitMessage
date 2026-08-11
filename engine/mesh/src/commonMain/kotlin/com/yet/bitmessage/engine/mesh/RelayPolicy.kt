package com.yet.bitmessage.engine.mesh

internal object RelayPolicy {
    fun outgoingTtl(received: UByte): UByte? {
        val capped = minOf(received.toInt(), MAX_RELAY_TTL)
        return if (capped < MIN_RELAY_INPUT_TTL) null else (capped - 1).toUByte()
    }

    private const val MAX_RELAY_TTL: Int = 7
    private const val MIN_RELAY_INPUT_TTL: Int = 2
}
