package com.yet.bitmessage.engine.mesh

import com.yet.bitmessage.foundation.CorrelationId
import com.yet.bitmessage.engine.mesh.runtime.MeshEffectExecutor
import com.yet.bitmessage.engine.mesh.runtime.executeEffect
import com.yet.bitmessage.foundation.Generation
import com.yet.bitmessage.foundation.TimerId
import com.yet.bitmessage.transport.api.LinkCommand
import com.yet.bitmessage.transport.api.LinkEvent
import com.yet.bitmessage.transport.api.LinkCloseReason
import com.yet.bitmessage.transport.api.LinkCapabilities
import com.yet.bitmessage.transport.api.LinkResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PendingLinkWriteTest {
    private val correlation = CorrelationId.of("write-test")
    private val timeout = TimerId.of("write-timeout")
    private val expiry = MeshFixtures.now.plus(15.seconds)

    private fun pendingState(): MeshState = MeshFixtures.state().copy(
        links = SnapshotMap(mapOf(MeshFixtures.linkB to ActiveLink(LinkCapabilities(4096, true), MeshFixtures.now))),
        pendingLinkWrites = SnapshotMap(mapOf(correlation to PendingLinkWrite(MeshFixtures.linkB, timeout, expiry))),
    )

    @Test
    fun thrownWriteExecutorFailureSettlesTheLiveLink() = runTest {
        val state = pendingState()
        val write = MeshEffect.WriteLink(LinkCommand.Write(
            MeshFixtures.linkB, correlation, MeshFixtures.generation, MeshFixtures.bytes("0102"),
        ))
        val failure = executeEffect(MeshEffectExecutor { throw IllegalStateException("failed write") }, write, MeshFixtures.now)
        val result = MeshEngine().reduce(state, requireNotNull(failure))
        assertTrue(result.state.pendingLinkWrites.isEmpty())
        assertTrue(result.state.links[MeshFixtures.linkB]?.capabilities?.writeReady == true)
    }

    @Test
    fun executionFailureSettlesItsCorrelatedWrite() {
        val state = pendingState()
        val result = MeshEngine().reduce(
            state,
            MeshEvent.EffectFailed(
                correlationId = correlation,
                generation = state.generation,
                observedAt = MeshFixtures.now,
                code = MeshFailureCode.EFFECT_EXECUTION_FAILED,
            ),
        )
        assertTrue(result.state.pendingLinkWrites.isEmpty())
        assertEquals(timeout, result.effects.filterIsInstance<MeshEffect.Cancel>().single().timerId)
    }

    @Test
    fun missingCompletionSettlesAtDeadlineAndLateCompletionIsIgnored() {
        val engine = MeshEngine()
        val expired = engine.reduce(pendingState(), MeshEvent.TimerElapsed(correlation, MeshFixtures.generation, expiry, timeout))
        assertTrue(expired.state.pendingLinkWrites.isEmpty())
        assertTrue(expired.state.links.containsKey(MeshFixtures.linkB))
        val late = engine.reduce(expired.state, completed())
        assertEquals(expired.state, late.state)
    }

    @Test
    fun completionCancelsDeadlineAndDuplicateOrStaleTimeoutCannotSettleAnotherWrite() {
        val engine = MeshEngine()
        val completed = engine.reduce(pendingState(), completed())
        assertTrue(completed.state.pendingLinkWrites.isEmpty())
        assertEquals(timeout, completed.effects.filterIsInstance<MeshEffect.Cancel>().single().timerId)
        assertEquals(completed.state, engine.reduce(completed.state, completed()).state)
        val replacement = completed.state.copy(
            pendingLinkWrites = SnapshotMap(mapOf(CorrelationId.of("new-write") to PendingLinkWrite(MeshFixtures.linkB, TimerId.of("new-timeout"), expiry))),
        )
        assertEquals(replacement, engine.reduce(replacement, MeshEvent.TimerElapsed(correlation, MeshFixtures.generation, expiry, timeout)).state)
        assertEquals(replacement, engine.reduce(replacement, MeshEvent.TimerElapsed(correlation, Generation(2), expiry, timeout)).state)
        assertEquals(replacement, engine.reduce(replacement, completed(generation = Generation(2))).state)
    }

    @Test
    fun linkCloseAndRuntimeStopClearPendingWrite() {
        val engine = MeshEngine()
        val closed = engine.reduce(pendingState(), MeshEvent.LinkObserved(
            MeshFixtures.generation, MeshFixtures.now, LinkEvent.Closed(MeshFixtures.linkB, LinkCloseReason.REMOTE_CLOSED),
        ))
        assertTrue(closed.state.pendingLinkWrites.isEmpty())
        assertEquals(timeout, closed.effects.filterIsInstance<MeshEffect.Cancel>().single().timerId)
        val stopped = engine.reduce(pendingState(), MeshEvent.RuntimeStopping(MeshFixtures.generation, MeshFixtures.now))
        assertTrue(stopped.state.pendingLinkWrites.isEmpty())
    }

    private fun completed(generation: Generation = MeshFixtures.generation): MeshEvent.LinkCompleted =
        MeshEvent.LinkCompleted(correlation, generation, MeshFixtures.now, LinkResult.Written(MeshFixtures.linkB, correlation, generation))
}
