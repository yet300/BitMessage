package com.yet.bitmessage.testing.compatibility

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class RegressionScenarioManifest(
    val formatVersion: Int,
    val profile: String,
    val scenarios: List<RegressionScenario>,
)

@Serializable
data class RegressionScenario(
    val id: String,
    val description: String,
    val targetPhase: String,
    val status: RegressionScenarioStatus,
    val provenance: ScenarioProvenance,
    val given: List<String>,
    @SerialName("when") val whenSteps: List<String>,
    val then: List<String>,
)

@Serializable
data class ScenarioProvenance(
    val sourceRepository: String,
    val sourceCommitSha: String,
    val sourcePath: String,
    val sourceTest: String,
)

@Serializable
enum class RegressionScenarioStatus {
    DEFERRED_UNTIL_PRODUCTION_COMPONENT_EXISTS,
}
