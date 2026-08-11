package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.testing.compatibility.FixtureManifestParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductionFixtureCoverageTest {
    @Test
    fun everyPhaseOneFixtureHasOneExplicitProductionCoverageStatus() {
        val manifest = FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json"))
        val report = ProductionFixtureCoverage.create(manifest)

        assertEquals(manifest.fixtures.map { it.id }.toSet(), report.entries.map { it.fixtureId }.toSet())
        assertEquals(manifest.fixtures.size, report.entries.size)
        assertEquals(29, report.entries.count { it.status == ProductionCoverageStatus.EXECUTED })
        assertEquals(
            setOf(
                "malformed-compression-size-abuse",
                "malformed-suspicious-compression-ratio",
                "malformed-truncated-signature",
                "malformed-invalid-padding",
                "malformed-unknown-message-type",
            ),
            report.entries.filter { it.status == ProductionCoverageStatus.EVIDENCE_LAYOUT_CONFLICT }.map { it.fixtureId }.toSet(),
        )
        assertTrue(report.entries.none { it.status == ProductionCoverageStatus.NOT_APPLICABLE })

        val reportDirectory = checkNotNull(System.getProperty(REPORT_DIRECTORY_PROPERTY)) {
            "Missing production coverage report directory."
        }
        val reportFile = File(reportDirectory, "phase1-4-production-fixture-coverage.json")
        ProductionFixtureCoverage.write(report, reportFile)
        assertTrue(reportFile.isFile)
    }

    private fun resource(path: String): String = checkNotNull(checkNotNull(javaClass.classLoader).getResource(path)) {
        "Missing compatibility resource $path"
    }.readText()

    private companion object {
        const val REPORT_DIRECTORY_PROPERTY = "bitchat.productionCoverageReportDirectory"
    }
}
