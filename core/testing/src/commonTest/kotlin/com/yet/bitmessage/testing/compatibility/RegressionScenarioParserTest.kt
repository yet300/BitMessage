package com.yet.bitmessage.testing.compatibility

import kotlin.test.Test
import kotlin.test.assertEquals

class RegressionScenarioParserTest {
    @Test
    fun parsesDeferredScenarioWithoutProductionTypes() {
        val manifest = RegressionScenarioParser.parse(
            """
            {
              "formatVersion": 1,
              "profile": "BitchatBaseline2026_08",
              "scenarios": [{
                "id": "dedup",
                "description": "authentication precedes dedup mutation",
                "targetPhase": "4",
                "status": "DEFERRED_UNTIL_PRODUCTION_COMPONENT_EXISTS",
                "provenance": {
                  "sourceRepository": "historical",
                  "sourceCommitSha": "10feab049becf4140c8bf10e0d9428c89222840f",
                  "sourcePath": "SecurityManagerTest.kt",
                  "sourceTest": "forged then valid"
                },
                "given": ["a forged packet"],
                "when": ["it fails authentication"],
                "then": ["dedup authority is unchanged"]
              }]
            }
            """.trimIndent(),
        )

        assertEquals("dedup", manifest.scenarios.single().id)
        assertEquals(listOf("it fails authentication"), manifest.scenarios.single().whenSteps)
    }
}
