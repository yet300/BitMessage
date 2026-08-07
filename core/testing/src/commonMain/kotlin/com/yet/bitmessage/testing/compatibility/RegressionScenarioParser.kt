package com.yet.bitmessage.testing.compatibility

import kotlinx.serialization.json.Json

object RegressionScenarioParser {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun parse(value: String): RegressionScenarioManifest = json.decodeFromString(value)
}
