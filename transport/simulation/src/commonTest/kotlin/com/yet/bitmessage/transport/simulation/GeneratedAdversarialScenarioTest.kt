package com.yet.bitmessage.transport.simulation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedAdversarialScenarioTest {
    @Test
    fun sixtyFourFixedSeedsPreserveBoundsAndAuthenticationBeforeDedup() = runTest {
        (0 until 64).forEach { seed ->
            val scenario = GeneratedScenarioCompiler.compile(seed)
            val snapshot = executeScenario(scenario, backgroundScope) { current ->
                assertTrue(current.pendingEvents.size <= scenario.simulationLimits.maxScheduledEvents, "seed=$seed")
                current.nodes.forEach { node ->
                    val state = node.state
                    assertTrue(state.pendingAdmissions.size <= scenario.meshLimits.maxPendingAdmissions, "seed=$seed")
                    assertTrue(state.admittedPackets.size <= scenario.meshLimits.maxAdmittedPacketIds, "seed=$seed")
                    assertTrue(state.fragmentStreams.size <= scenario.meshLimits.maxFragmentStreams, "seed=$seed")
                    assertTrue(state.aggregateFragmentBytes <= scenario.meshLimits.maxAggregateFragmentBytes, "seed=$seed")
                    assertTrue(state.scheduledRelays.size <= scenario.meshLimits.maxScheduledRelays, "seed=$seed")
                    assertEquals(node.publications.map { it.packetId }.distinct(),
                        node.publications.map { it.packetId }, "seed=$seed")
                    assertTrue(scenario.invalidAuthenticationPacketIds.none { it in state.admittedPackets },
                        "invalid authentication poisoned dedup, seed=$seed")
                }
            }.finalSnapshot
            assertTrue(snapshot.processedEvents <= scenario.simulationLimits.maxProcessedEvents, "seed=$seed")
            assertTrue(snapshot.deliveries.all { delivery ->
                delivery.ttl == null || delivery.ttl.toInt() in 0..7
            }, "seed=$seed")
        }
    }
}
