package com.yet.bitmessage.foundation

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineTest {

    @Test
    fun reductionIsDeterministicAndPreservesEffectAndTraceOrder() {
        val engine = CountingEngine()
        val state = CounterState(count = 4)

        val first = engine.reduce(state, CounterEvent.Increment)
        val second = engine.reduce(state, CounterEvent.Increment)

        assertEquals(CounterState(count = 5), first.state)
        assertEquals(listOf(CounterEffect.Record, CounterEffect.Notify), first.effects)
        assertEquals(
            listOf(
                TraceRecord(
                    transitionName = TransitionName.of("counter-increment"),
                    decision = TraceDecision.APPLIED,
                    sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
                ),
                TraceRecord(
                    transitionName = TransitionName.of("counter-notify"),
                    decision = TraceDecision.SCHEDULED,
                    sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
                ),
            ),
            first.trace,
        )
        assertEquals(first, second)
    }

    @Test
    fun transitionOwnsEffectAndTraceListInputs() {
        val effects = mutableListOf(CounterEffect.Record)
        val trace = mutableListOf(
            TraceRecord(
                transitionName = TransitionName.of("counter-record"),
                decision = TraceDecision.APPLIED,
                sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
            ),
        )

        val transition = Transition(CounterState(count = 1), effects, trace)
        effects += CounterEffect.Notify
        trace.clear()

        assertEquals(listOf(CounterEffect.Record), transition.effects)
        assertEquals(1, transition.trace.size)
    }

    @Test
    fun transitionRetainsSnapshotsWhenAnExposedListIsMutable() {
        val originalTrace = TraceRecord(
            transitionName = TransitionName.of("counter-record"),
            decision = TraceDecision.APPLIED,
            sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
        )
        val secondTrace = TraceRecord(
            transitionName = TransitionName.of("counter-notify"),
            decision = TraceDecision.SCHEDULED,
            sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
        )
        val transition = Transition(
            state = CounterState(count = 1),
            effects = listOf(CounterEffect.Record, CounterEffect.Notify),
            trace = listOf(originalTrace, secondTrace),
        )

        attemptMutation(transition.effects, CounterEffect.Record)
        attemptMutation(
            transition.trace,
            TraceRecord(
                transitionName = TransitionName.of("counter-notify"),
                decision = TraceDecision.SCHEDULED,
                sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
            ),
        )

        assertEquals(listOf(CounterEffect.Record, CounterEffect.Notify), transition.effects)
        assertEquals(listOf(originalTrace, secondTrace), transition.trace)
    }

    private class CountingEngine : Engine<CounterState, CounterEvent, CounterEffect> {
        override fun reduce(state: CounterState, event: CounterEvent): Transition<CounterState, CounterEffect> =
            when (event) {
                CounterEvent.Increment -> Transition(
                    state = CounterState(state.count + 1),
                    effects = listOf(CounterEffect.Record, CounterEffect.Notify),
                    trace = listOf(
                        TraceRecord(
                            transitionName = TransitionName.of("counter-increment"),
                            decision = TraceDecision.APPLIED,
                            sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
                        ),
                        TraceRecord(
                            transitionName = TransitionName.of("counter-notify"),
                            decision = TraceDecision.SCHEDULED,
                            sizeFacts = listOf(TraceSize(TraceSizeKind.ITEM_COUNT, 1)),
                        ),
                    ),
                )
            }
    }

    private data class CounterState(
        val count: Int,
    )

    private sealed interface CounterEvent {
        data object Increment : CounterEvent
    }

    private enum class CounterEffect {
        Record,
        Notify,
    }

    private fun <T> attemptMutation(values: List<T>, value: T) {
        (values as? MutableList<T>)?.add(value)
    }
}
