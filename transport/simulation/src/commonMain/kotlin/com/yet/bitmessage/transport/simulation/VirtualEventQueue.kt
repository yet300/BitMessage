package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.MonotonicTime

data class ScheduledEvent<out T>(
    val id: ScheduledEventId,
    val deadline: MonotonicTime,
    val sequence: Long,
    val value: T,
)

/** The only ordered future-event store; runtime actor mailboxes remain independent. */
class VirtualEventQueue<T>(
    private val capacity: Int,
    initialSequence: Long = 0,
) {
    init {
        require(capacity > 0)
        require(initialSequence >= 0)
    }

    private val scheduled = mutableListOf<ScheduledEvent<T>>()
    private var nextSequence = initialSequence

    val size: Int get() = scheduled.size

    fun schedule(deadline: MonotonicTime, value: T): QueueScheduleResult {
        if (nextSequence == Long.MAX_VALUE) return QueueScheduleResult.SequenceExhausted
        if (scheduled.size >= capacity) return QueueScheduleResult.Full

        val sequence = nextSequence++
        val entry = ScheduledEvent(ScheduledEventId(sequence), deadline, sequence, value)
        val insertionIndex = scheduled.indexOfFirst {
            it.deadline > deadline || (it.deadline == deadline && it.sequence > sequence)
        }
        if (insertionIndex < 0) scheduled.add(entry) else scheduled.add(insertionIndex, entry)
        return QueueScheduleResult.Scheduled(entry.id)
    }

    fun peek(): ScheduledEvent<T>? = scheduled.firstOrNull()

    /** Does not move virtual time or consume a past/future deadline. */
    fun removeNextDue(now: MonotonicTime): ScheduledEvent<T>? =
        if (scheduled.firstOrNull()?.deadline == now) scheduled.removeAt(0) else null

    fun cancel(id: ScheduledEventId): Boolean {
        val index = scheduled.indexOfFirst { it.id == id }
        if (index < 0) return false
        scheduled.removeAt(index)
        return true
    }

    fun entries(): List<ScheduledEvent<T>> = scheduled.toList()
}
