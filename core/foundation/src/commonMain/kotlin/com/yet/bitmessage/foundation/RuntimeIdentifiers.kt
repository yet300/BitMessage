package com.yet.bitmessage.foundation

import kotlin.jvm.JvmInline

@JvmInline
value class TimerId private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): TimerId {
            require(value.isNotBlank()) { "Timer ID must not be blank." }
            return TimerId(value)
        }
    }
}

@JvmInline
value class CorrelationId private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): CorrelationId {
            require(value.isNotBlank()) { "Correlation ID must not be blank." }
            return CorrelationId(value)
        }
    }
}

@JvmInline
value class Generation(
    val value: Long,
) {
    init {
        require(value >= 0) { "Generation must not be negative." }
    }

    fun next(): Generation {
        check(value < Long.MAX_VALUE) { "Generation cannot advance beyond Long.MAX_VALUE." }
        return Generation(value + 1)
    }
}
