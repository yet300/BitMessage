package com.yet.bitmessage.testing.runtime

import com.yet.bitmessage.foundation.CancelTimer
import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.foundation.EntropyRequest
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.foundation.ScheduleTimer
import com.yet.bitmessage.foundation.TimerFired
import com.yet.bitmessage.foundation.TimerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class VirtualRuntimeTest {

    @Test
    fun virtualClocksAdvanceOnlyWhenExplicitlyInstructed() {
        val wallClock = VirtualWallClock(Instant.fromEpochMilliseconds(1_000))
        val monotonicClock = VirtualMonotonicClock()
        val start = monotonicClock.now()

        assertEquals(Instant.fromEpochMilliseconds(1_000), wallClock.now())
        assertEquals(0.seconds, monotonicClock.now().elapsedSince(start))

        wallClock.advanceBy(3.seconds)
        monotonicClock.advanceBy(3.seconds)

        assertEquals(Instant.fromEpochMilliseconds(4_000), wallClock.now())
        assertEquals(3.seconds, monotonicClock.now().elapsedSince(start))
        assertFailsWith<IllegalArgumentException> { wallClock.advanceBy((-1).seconds) }
        assertFailsWith<IllegalArgumentException> { monotonicClock.advanceBy((-1).seconds) }
    }

    @Test
    fun schedulerOrdersByAbsoluteDeadlineThenInsertionSequence() {
        val scheduler = VirtualScheduler()
        val first = TimerId.of("first")
        val second = TimerId.of("second")
        val third = TimerId.of("third")

        scheduler.schedule(ScheduleTimer(first, 2.seconds, Generation(0)))
        scheduler.schedule(ScheduleTimer(second, 1.seconds, Generation(0)))
        scheduler.schedule(ScheduleTimer(third, 1.seconds, Generation(0)))

        assertEquals(
            listOf(
                TimerFired(second, Generation(0)),
                TimerFired(third, Generation(0)),
            ),
            scheduler.advanceBy(1.seconds),
        )
        assertEquals(listOf(TimerFired(first, Generation(0))), scheduler.advanceBy(1.seconds))
    }

    @Test
    fun schedulerFiresOnlyWhenVirtualTimeAdvancesToTheDeadline() {
        val scheduler = VirtualScheduler()
        val timerId = TimerId.of("retry")

        scheduler.schedule(ScheduleTimer(timerId, 2.seconds, Generation(0)))

        assertTrue(scheduler.advanceBy(1.seconds).isEmpty())
        assertEquals(listOf(TimerFired(timerId, Generation(0))), scheduler.advanceBy(1.seconds))
    }

    @Test
    fun cancellationSuppressesPendingTimerFirings() {
        val scheduler = VirtualScheduler()
        val timerId = TimerId.of("retry")

        scheduler.schedule(ScheduleTimer(timerId, 1.seconds, Generation(0)))
        scheduler.cancel(CancelTimer(timerId))

        assertTrue(scheduler.advanceBy(1.seconds).isEmpty())
    }

    @Test
    fun reschedulingDiscardsOlderPendingEntriesForTheSameTimer() {
        val scheduler = VirtualScheduler()
        val timerId = TimerId.of("retry")

        scheduler.schedule(ScheduleTimer(timerId, 1.seconds, Generation(0)))
        scheduler.schedule(ScheduleTimer(timerId, 2.seconds, Generation(1)))

        assertTrue(scheduler.advanceBy(1.seconds).isEmpty())
        assertEquals(listOf(TimerFired(timerId, Generation(1))), scheduler.advanceBy(1.seconds))
    }

    @Test
    fun schedulerPreservesGenerationSoStalenessRemainsObservable() {
        val scheduler = VirtualScheduler()
        val timerId = TimerId.of("retry")
        val staleGeneration = Generation(4)

        scheduler.schedule(ScheduleTimer(timerId, 1.seconds, staleGeneration))

        assertEquals(listOf(TimerFired(timerId, staleGeneration)), scheduler.advanceBy(1.seconds))
    }

    @Test
    fun schedulingAndEntropyRequestsRejectNegativeValues() {
        val timerId = TimerId.of("retry")
        val correlationId = CorrelationId.of("entropy")

        assertFailsWith<IllegalArgumentException> {
            ScheduleTimer(timerId, (-1).seconds, Generation(0))
        }
        assertFailsWith<IllegalArgumentException> { EntropyRequest(correlationId, -1) }
        assertFailsWith<IllegalArgumentException> { VirtualScheduler().advanceBy((-1).seconds) }
    }

    @Test
    fun timeAndSchedulingRejectNonFiniteDurations() {
        val timerId = TimerId.of("retry")
        val wallClock = VirtualWallClock(Instant.fromEpochMilliseconds(1_000))
        val negativeInfinity = -Duration.INFINITE

        assertFailsWith<IllegalArgumentException> { MonotonicTime.fromElapsed(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { MonotonicTime.ZERO.plus(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> {
            ScheduleTimer(timerId, Duration.INFINITE, Generation(0))
        }
        assertFailsWith<IllegalArgumentException> { wallClock.advanceBy(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { VirtualMonotonicClock().advanceBy(Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { VirtualScheduler().advanceBy(Duration.INFINITE) }

        assertFailsWith<IllegalArgumentException> { MonotonicTime.fromElapsed(negativeInfinity) }
        assertFailsWith<IllegalArgumentException> { MonotonicTime.ZERO.plus(negativeInfinity) }
        assertFailsWith<IllegalArgumentException> {
            ScheduleTimer(timerId, negativeInfinity, Generation(0))
        }
        assertFailsWith<IllegalArgumentException> { wallClock.advanceBy(negativeInfinity) }
        assertFailsWith<IllegalArgumentException> { VirtualMonotonicClock().advanceBy(negativeInfinity) }
        assertFailsWith<IllegalArgumentException> { VirtualScheduler().advanceBy(negativeInfinity) }
    }

    @Test
    fun monotonicTimeRejectsFiniteDurationAdditionThatOverflows() {
        val largestFiniteMilliseconds = (Long.MAX_VALUE / 2 - 1).milliseconds

        assertTrue(largestFiniteMilliseconds.isFinite())
        assertFailsWith<IllegalArgumentException> {
            MonotonicTime.fromElapsed(largestFiniteMilliseconds).plus(largestFiniteMilliseconds)
        }
    }

    @Test
    fun sequenceExhaustionDoesNotDiscardAnExistingTimer() {
        val scheduler = VirtualScheduler(Long.MAX_VALUE - 1)
        val timerId = TimerId.of("retry")

        scheduler.schedule(ScheduleTimer(timerId, 1.seconds, Generation(0)))

        assertFailsWith<IllegalStateException> {
            scheduler.schedule(ScheduleTimer(timerId, 2.seconds, Generation(1)))
        }
        assertEquals(listOf(TimerFired(timerId, Generation(0))), scheduler.advanceBy(1.seconds))
    }

    @Test
    fun seededEntropyReplaysTheSameSequenceWithoutChangingCorrelation() {
        val requests = listOf(
            EntropyRequest(CorrelationId.of("entropy-first"), 4),
            EntropyRequest(CorrelationId.of("entropy-second"), 2),
        )
        val first = SeededEntropy(seed = 23)
        val second = SeededEntropy(seed = 23)

        val firstResults = requests.map(first::generate)
        val secondResults = requests.map(second::generate)

        assertEquals(firstResults, secondResults)
        assertEquals(requests.first().correlationId, firstResults.first().correlationId)
        assertEquals(4, firstResults.first().bytes.size)
        assertEquals(0, SeededEntropy(seed = 23).generate(EntropyRequest(requests.first().correlationId, 0)).bytes.size)
    }
}
