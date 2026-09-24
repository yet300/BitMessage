package com.yet.bitmessage.transport.simulation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeterministicReplayTest {
    @Test
    fun identicalCompiledInputsProduceIdenticalStateTraceAndQueue() = runTest {
        val scenario = GeneratedScenarioCompiler.compile(0x5eed, 64)
        val first = executeScenario(scenario, backgroundScope).finalSnapshot
        val second = executeScenario(scenario, backgroundScope).finalSnapshot
        assertEquals(first, second)
    }

    @Test
    fun boundedFailurePreservesReplayInputsWithoutRawPayloads() = runTest {
        val scenario = GeneratedScenarioCompiler.compile(17)
        val failure = try {
            executeScenario(scenario, backgroundScope, eventLimit = 4)
            error("Expected an event-limit failure")
        } catch (expected: SimulationFailure) { expected }
        assertEquals(scenario.name, failure.scenarioName)
        assertEquals(scenario.seed, failure.seed)
        assertEquals(scenario.topology, failure.topology)
        assertEquals(scenario.actions, failure.actions)
        assertEquals(scenario.faultPlan, failure.faultPlan)
        assertEquals(scenario.protocolSeeds, failure.protocolSeeds)
        assertTrue(failure.lastTrace.size <= 64)
        assertTrue(failure.render().contains("seed=17"))
        assertFalse(failure.render().contains("private-payload"))
    }
}
