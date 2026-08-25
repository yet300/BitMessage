import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

val simulationResultFiles = fileTree(layout.buildDirectory.dir("test-results/testAndroidHostTest")) {
    include("TEST-*.xml")
}

val cleanSimulationTestResults = tasks.register<Delete>("cleanSimulationTestResults") {
    group = "verification"
    description = "Removes prior Android-host simulation results before the Phase 5 gate runs."
    delete(layout.buildDirectory.dir("test-results/testAndroidHostTest"))
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    mustRunAfter(cleanSimulationTestResults)
}

tasks.register("simulationCheck") {
    group = "verification"
    description = "Runs fresh Phase 5 simulator tests and fails when the coverage anchor did not execute."
    dependsOn(cleanSimulationTestResults, "testAndroidHostTest")
    inputs.files(simulationResultFiles)

    doLast {
        val result = requireNotNull(
            inputs.files.singleOrNull {
                it.name == "TEST-com.yet.bitmessage.transport.simulation.SimulationCoverageTest.xml"
            },
        ) { "Phase 5 simulation gate requires a fresh SimulationCoverageTest result." }
        val tests = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(result)
            .documentElement
            .getAttribute("tests")
            .toInt()
        check(tests > 0) { "Phase 5 simulation gate requires executed coverage; found $tests." }
        logger.lifecycle("Phase 5 simulation gate executed {} coverage tests.", tests)
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
            implementation(projects.protocol.bitchat)
            implementation(projects.transport.api)
            implementation(projects.engine.mesh)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        androidHostTest {
            resources.srcDir(rootProject.layout.projectDirectory.dir("compatibility"))
        }
    }
}
