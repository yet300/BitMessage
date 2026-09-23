package com.yet.bitmessage.transport.simulation

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.foundation.MonotonicTime
import com.yet.bitmessage.model.LinkId
import com.yet.bitmessage.transport.api.LinkEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

class VirtualEventQueueTest {
    @Test
    fun ordersByDeadlineThenInsertionSequenceWithoutAdvancingTime() {
        val queue = VirtualEventQueue<String>(capacity = 4)
        val later = MonotonicTime.ZERO.plus(100.milliseconds)
        queue.schedule(later, "late")
        queue.schedule(MonotonicTime.ZERO, "first")
        queue.schedule(MonotonicTime.ZERO, "second")

        assertEquals("first", queue.removeNextDue(MonotonicTime.ZERO)?.value)
        assertEquals("second", queue.removeNextDue(MonotonicTime.ZERO)?.value)
        assertNull(queue.removeNextDue(MonotonicTime.ZERO))
        assertEquals("late", queue.peek()?.value)
        assertEquals("late", queue.removeNextDue(later)?.value)
        assertNull(queue.peek())
    }

    @Test
    fun cancellationReusesCapacityAndSequenceExhaustionIsTyped() {
        val exhausted = VirtualEventQueue<String>(capacity = 1, initialSequence = Long.MAX_VALUE)
        assertIs<QueueScheduleResult.SequenceExhausted>(
            exhausted.schedule(MonotonicTime.ZERO, "never"),
        )
        assertEquals(0, exhausted.size)

        val bounded = VirtualEventQueue<String>(capacity = 1)
        val accepted = assertIs<QueueScheduleResult.Scheduled>(
            bounded.schedule(MonotonicTime.ZERO, "one"),
        )
        assertIs<QueueScheduleResult.Full>(bounded.schedule(MonotonicTime.ZERO, "two"))
        assertTrue(bounded.cancel(accepted.id))
        assertFalse(bounded.cancel(accepted.id))
        val second = assertIs<QueueScheduleResult.Scheduled>(
            bounded.schedule(MonotonicTime.ZERO, "two"),
        )
        assertTrue(second.id.value > accepted.id.value)
    }

    @Test
    fun queueExposesStableOrderedMetadataAndRequiresPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { VirtualEventQueue<String>(capacity = 0) }
        assertFailsWith<IllegalArgumentException> {
            VirtualEventQueue<String>(capacity = 1, initialSequence = -1)
        }
        val queue = VirtualEventQueue<String>(capacity = 3, initialSequence = 7)
        val later = MonotonicTime.ZERO.plus(1.minutes)
        val a = assertIs<QueueScheduleResult.Scheduled>(queue.schedule(later, "a"))
        val b = assertIs<QueueScheduleResult.Scheduled>(queue.schedule(MonotonicTime.ZERO, "b"))
        assertEquals(listOf(b.id, a.id), queue.entries().map { it.id })
        assertEquals(listOf(8L, 7L), queue.entries().map { it.sequence })
        assertNull(queue.removeNextDue(later.plus(1.milliseconds)))
    }

    @Test
    fun identifiersNormalizeAndRejectBlankValues() {
        assertEquals("node-a", SimulatedNodeId.of(" node-a ").value)
        assertEquals("link-a", SimulatedLinkId.of(" link-a ").value)
        assertFailsWith<IllegalArgumentException> { SimulatedNodeId.of("  ") }
        assertFailsWith<IllegalArgumentException> { SimulatedLinkId.of("") }
    }

    @Test
    fun allSimulationLimitsRequirePositiveBounds() {
        val valid = SimulationLimits()
        val invalid = listOf<() -> SimulationLimits>(
            { valid.copy(maxNodes = 0) },
            { valid.copy(maxDirectedLinks = -1) },
            { valid.copy(maxScheduledEvents = 0) },
            { valid.copy(maxFaultActions = 0) },
            { valid.copy(maxTraceRecords = 0) },
            { valid.copy(maxPublicationRecords = 0) },
            { valid.copy(maxDeliveryRecords = 0) },
            { valid.copy(maxCompletionRecords = 0) },
            { valid.copy(maxProcessedEvents = 0) },
            { valid.copy(maxVirtualDuration = kotlin.time.Duration.ZERO) },
            { valid.copy(maxVirtualDuration = kotlin.time.Duration.INFINITE) },
        )
        invalid.forEach { create ->
            assertFailsWith<IllegalArgumentException> { create() }
        }
    }

    @Test
    fun boundedTraceKeepsNewestRecordsAndExactDropCount() {
        val trace = BoundedSimulationTrace(capacity = 2)
        val first = record(0)
        val second = record(1)
        val third = record(2)
        trace.append(first)
        trace.append(second)
        trace.append(third)

        assertEquals(listOf(second, third), trace.records())
        assertEquals(1L, trace.droppedCount)
        assertFailsWith<IllegalArgumentException> { BoundedSimulationTrace(capacity = 0) }
    }

    @Test
    fun payloadCannotBypassProtocolAdapterThroughLinkObservation() {
        assertFailsWith<IllegalArgumentException> {
            SimulationEvent.ObserveLink(
                targetNode = SimulatedNodeId.of("node-a"),
                event = LinkEvent.PayloadReceived(
                    linkId = LinkId.of("link-a"),
                    bytes = Bytes.copyOf(byteArrayOf(1)),
                ),
            )
        }
    }

    @Test
    fun scheduledMeshResultsRemainPacketNetworkWorkButMaintenanceTimersDoNot() {
        assertTrue(ScheduledCategory.MESH_EVENT.isPacketNetworkWork)
        assertFalse(ScheduledCategory.RUNTIME_TIMER.isPacketNetworkWork)
    }

    private fun record(sequence: Long) = SimulationTraceRecord(
        time = MonotonicTime.ZERO,
        sequence = sequence,
        category = TraceCategory.SCHEDULED,
        outcome = TraceOutcome.SCHEDULED,
    )
}
