import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

kotlin {
    sourceSets {
        androidHostTest {
            resources.srcDir(rootProject.layout.projectDirectory.dir("compatibility"))
        }
    }
}

val compatibilityResultFiles = fileTree(layout.buildDirectory.dir("test-results/testAndroidHostTest")) {
    include("TEST-*.xml")
}

val cleanCompatibilityTestResults = tasks.register<Delete>("cleanCompatibilityTestResults") {
    group = "verification"
    description = "Removes prior Android-host compatibility results before the Phase 1 gate runs."
    delete(layout.buildDirectory.dir("test-results/testAndroidHostTest"))
}

val compatibilityTest = tasks.matching { it.name == "testAndroidHostTest" }
compatibilityTest.configureEach {
    mustRunAfter(cleanCompatibilityTestResults)
}

tasks.register("compatibilityCheck") {
    group = "verification"
    description = "Runs the fresh Phase 1 corpus suite and fails when it did not execute."
    dependsOn(cleanCompatibilityTestResults, "testAndroidHostTest")
    inputs.files(compatibilityResultFiles)

    doLast {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val corpusResult = requireNotNull(
            inputs.files.singleOrNull {
                it.name == "TEST-com.yet.bitmessage.testing.compatibility.CompatibilityCorpusTest.xml"
            },
        ) {
            "Phase 1 compatibility gate requires a fresh CompatibilityCorpusTest result."
        }
        check(corpusResult.isFile) {
            "Phase 1 compatibility gate requires a fresh CompatibilityCorpusTest result."
        }

        val executedTests = inputs.files.filter { it.isFile }.sumOf { resultFile ->
            documentBuilder.parse(resultFile).documentElement.getAttribute("tests").toInt()
        }

        val corpusTests = documentBuilder.parse(corpusResult).documentElement.getAttribute("tests").toInt()

        check(executedTests > 0) {
            "Phase 1 compatibility gate requires executed tests; found $executedTests."
        }
        check(corpusTests > 0) {
            "Phase 1 compatibility gate requires CompatibilityCorpusTest to execute; found $corpusTests."
        }
        logger.lifecycle(
            "Phase 1 compatibility gate executed {} Android-host tests ({} corpus tests).",
            executedTests,
            corpusTests,
        )
    }
}
