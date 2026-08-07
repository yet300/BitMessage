package com.yet.bitmessage.testing.runtime

import com.yet.bitmessage.foundation.MonotonicClock
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.WallClock
import kotlin.time.Duration
import kotlin.time.Instant

class VirtualWallClock(
    initial: Instant,
) : WallClock {
    private var current: Instant = initial

    override fun now(): Instant = current

    fun advanceBy(duration: Duration) {
        require(duration.isFinite() && duration >= Duration.ZERO) {
            "Virtual wall time must advance by a finite nonnegative duration."
        }
        current += duration
    }
}

class VirtualMonotonicClock(
    initial: MonotonicTime = MonotonicTime.ZERO,
) : MonotonicClock {
    private var current: MonotonicTime = initial

    override fun now(): MonotonicTime = current

    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}
