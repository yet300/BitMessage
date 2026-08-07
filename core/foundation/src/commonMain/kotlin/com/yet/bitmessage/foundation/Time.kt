package com.yet.bitmessage.foundation

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

fun interface WallClock {
    fun now(): Instant
}

fun interface MonotonicClock {
    fun now(): MonotonicTime
}

/**
 * A process-local elapsed-time point. It must not be serialized or treated as durable wall time.
 */
@JvmInline
value class MonotonicTime private constructor(
    private val elapsed: Duration,
) : Comparable<MonotonicTime> {

    fun elapsedSince(earlier: MonotonicTime): Duration = elapsed - earlier.elapsed

    fun plus(duration: Duration): MonotonicTime {
        require(duration.isFinite() && duration >= Duration.ZERO) {
            "Monotonic time must advance by a finite nonnegative duration."
        }
        val updatedElapsed = elapsed + duration
        require(updatedElapsed.isFinite()) { "Monotonic time must remain finite." }
        return MonotonicTime(updatedElapsed)
    }

    override fun compareTo(other: MonotonicTime): Int = elapsed.compareTo(other.elapsed)

    companion object {
        val ZERO: MonotonicTime = MonotonicTime(Duration.ZERO)

        fun fromElapsed(elapsed: Duration): MonotonicTime {
            require(elapsed.isFinite() && elapsed >= Duration.ZERO) {
                "Monotonic elapsed time must be finite and nonnegative."
            }
            return MonotonicTime(elapsed)
        }
    }
}
