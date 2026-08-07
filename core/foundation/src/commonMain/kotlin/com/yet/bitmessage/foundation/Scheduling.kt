package com.yet.bitmessage.foundation

import kotlin.time.Duration

data class ScheduleTimer(
    val timerId: TimerId,
    val delay: Duration,
    val generation: Generation,
) {
    init {
        require(delay.isFinite() && delay >= Duration.ZERO) {
            "Timer delay must be finite and nonnegative."
        }
    }
}

data class CancelTimer(
    val timerId: TimerId,
)

data class TimerFired(
    val timerId: TimerId,
    val generation: Generation,
)
