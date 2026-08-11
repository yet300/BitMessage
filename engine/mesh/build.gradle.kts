import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

val meshEngineResultFiles = fileTree(layout.buildDirectory.dir("test-results/testAndroidHostTest")) {
    include("TEST-*.xml")
}

val cleanMeshEngineTestResults = tasks.register<Delete>("cleanMeshEngineTestResults") {
    group = "verification"
    description = "Removes prior Android-host mesh results before the Phase 4 gate runs."
    delete(layout.buildDirectory.dir("test-results/testAndroidHostTest"))
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    mustRunAfter(cleanMeshEngineTestResults)
}

tasks.register("meshEngineCheck") {
    group = "verification"
    description = "Runs fresh Phase 4 mesh tests and fails when the coverage anchor did not execute."
    dependsOn(cleanMeshEngineTestResults, "testAndroidHostTest")
    inputs.files(meshEngineResultFiles)

    doLast {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val coverageResult = requireNotNull(
            inputs.files.singleOrNull {
                it.name == "TEST-com.yet.bitmessage.engine.mesh.MeshEngineCoverageTest.xml"
            },
        ) {
            "Phase 4 mesh gate requires a fresh MeshEngineCoverageTest result."
        }
        check(coverageResult.isFile) {
            "Phase 4 mesh gate requires a fresh MeshEngineCoverageTest result."
        }
        val executedTests = documentBuilder.parse(coverageResult).documentElement.getAttribute("tests").toInt()
        check(executedTests > 0) {
            "Phase 4 mesh gate requires MeshEngineCoverageTest to execute; found $executedTests."
        }
        logger.lifecycle("Phase 4 mesh engine gate executed {} coverage tests.", executedTests)
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
            implementation(projects.protocol.bitchat)
            implementation(projects.transport.api)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
