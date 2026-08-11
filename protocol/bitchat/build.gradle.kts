import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.local.kotlin.multiplatform)
}

val productionCompatibilityResultFiles = fileTree(layout.buildDirectory.dir("test-results/testAndroidHostTest")) {
    include("TEST-*.xml")
}

val cleanProductionCompatibilityTestResults = tasks.register<Delete>("cleanProductionCompatibilityTestResults") {
    group = "verification"
    description = "Removes prior Android-host protocol results before the Phase 1-4 production gate runs."
    delete(layout.buildDirectory.dir("test-results/testAndroidHostTest"))
}

val productionCompatibilityTest = tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }
productionCompatibilityTest.configureEach {
    mustRunAfter(cleanProductionCompatibilityTestResults)
    systemProperty(
        "bitchat.productionCoverageReportDirectory",
        layout.buildDirectory.dir("reports/production-coverage").get().asFile.absolutePath,
    )
}

tasks.register("productionCompatibilityCheck") {
    group = "verification"
    description = "Runs the fresh Phase 1-4 production coverage report and fails when it did not execute."
    dependsOn(cleanProductionCompatibilityTestResults, "testAndroidHostTest")
    inputs.files(productionCompatibilityResultFiles)

    doLast {
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val coverageResult = requireNotNull(
            inputs.files.singleOrNull {
                it.name == "TEST-com.yet.bitmessage.protocol.bitchat.ProductionFixtureCoverageTest.xml"
            },
        ) {
            "Phase 1-4 production gate requires a fresh ProductionFixtureCoverageTest result."
        }
        check(coverageResult.isFile) {
            "Phase 1-4 production gate requires a fresh ProductionFixtureCoverageTest result."
        }
        val executedTests = documentBuilder.parse(coverageResult).documentElement.getAttribute("tests").toInt()
        check(executedTests > 0) {
            "Phase 1-4 production gate requires ProductionFixtureCoverageTest to execute; found $executedTests."
        }
        logger.lifecycle("Phase 1-4 production compatibility gate executed {} coverage tests.", executedTests)
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.foundation)
            implementation(projects.core.model)
        }

        androidHostTest {
            dependencies {
                implementation(projects.core.testing)
            }
            resources.srcDir(rootProject.layout.projectDirectory.dir("compatibility"))
        }
    }
}
