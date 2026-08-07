package com.yet.bitmessage.testing.runtime

import com.yet.bitmessage.foundation.CancelTimer
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.ScheduleTimer
import com.yet.bitmessage.foundation.TimerFired
import com.yet.bitmessage.foundation.TimerId
import kotlin.time.Duration

class VirtualScheduler {
    private val pendingTimers = mutableListOf<PendingTimer>()
    private var currentTime = MonotonicTime.ZERO
    private var nextSequence = 0L

    constructor()

    internal constructor(initialSequence: Long) {
        require(initialSequence >= 0) { "Virtual scheduler sequence must not be negative." }
        nextSequence = initialSequence
    }

    val now: MonotonicTime
        get() = currentTime

    fun schedule(timer: ScheduleTimer) {
        val replacement = PendingTimer(
            deadline = currentTime.plus(timer.delay),
            sequence = nextInsertionSequence(),
            fired = TimerFired(timer.timerId, timer.generation),
        )
        pendingTimers.removeAll { it.fired.timerId == timer.timerId }
        pendingTimers += replacement
    }

    fun cancel(timer: CancelTimer) {
        pendingTimers.removeAll { it.fired.timerId == timer.timerId }
    }

    fun advanceBy(duration: Duration): List<TimerFired> {
        require(duration.isFinite() && duration >= Duration.ZERO) {
            "Virtual scheduler must advance by a finite nonnegative duration."
        }
        val targetTime = currentTime.plus(duration)
        val dueTimers = pendingTimers
            .filter { it.deadline <= targetTime }
            .sortedWith(compareBy<PendingTimer> { it.deadline }.thenBy { it.sequence })

        pendingTimers.removeAll(dueTimers.toSet())
        currentTime = targetTime
        return dueTimers.map(PendingTimer::fired)
    }

    private fun nextInsertionSequence(): Long {
        check(nextSequence < Long.MAX_VALUE) { "Virtual scheduler insertion sequence exhausted." }
        return nextSequence++
    }

    private data class PendingTimer(
        val deadline: MonotonicTime,
        val sequence: Long,
        val fired: TimerFired,
    )
}
