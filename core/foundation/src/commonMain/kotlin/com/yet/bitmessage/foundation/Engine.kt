package com.yet.bitmessage.foundation

interface Engine<S : Any, E : Any, F : Any> {
    fun reduce(state: S, event: E): Transition<S, F>
}

class Transition<S : Any, F : Any>(
    val state: S,
    effects: List<F> = emptyList(),
    trace: List<TraceRecord> = emptyList(),
) {
    private val effectsSnapshot = effects.toList()
    private val traceSnapshot = trace.toList()

    val effects: List<F>
        get() = effectsSnapshot.toList()

    val trace: List<TraceRecord>
        get() = traceSnapshot.toList()

    override fun equals(other: Any?): Boolean =
        other is Transition<*, *> &&
            state == other.state &&
            effectsSnapshot == other.effectsSnapshot &&
            traceSnapshot == other.traceSnapshot

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + effectsSnapshot.hashCode()
        result = 31 * result + traceSnapshot.hashCode()
        return result
    }
}
