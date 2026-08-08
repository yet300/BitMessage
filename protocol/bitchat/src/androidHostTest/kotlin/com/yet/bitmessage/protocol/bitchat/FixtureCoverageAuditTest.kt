package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.testing.compatibility.CompatibilityFixture
import com.yet.bitmessage.testing.compatibility.FixtureCategory
import com.yet.bitmessage.testing.compatibility.FixtureDecisionState
import com.yet.bitmessage.testing.compatibility.FixtureManifestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureCoverageAuditTest {

    @Test
    fun phaseOneCorpusHasOneEvidenceFirstStatusForEveryFixture() {
        val fixtures = FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json")).fixtures

        assertEquals(46, fixtures.size)
        assertEquals(46, fixtures.associate { it.id to classify(it) }.size)
        assertEquals(CoverageStatus.IMPLEMENT_NOW, classify(fixtures.single { it.id == "apple-v2-route" }))
        assertEquals(CoverageStatus.BLOCKED, classify(fixtures.single { it.id == "drift-neighbor-encoding-ambiguity" }))
    }

    @Test
    fun knownEvidenceConflictsCannotBeClaimedAsProductionDecodes() {
        val fixtures = FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json")).fixtures
        val conflicts = setOf(
            "malformed-compression-size-abuse",
            "malformed-suspicious-compression-ratio",
            "malformed-truncated-signature",
            "malformed-invalid-padding",
            "malformed-unknown-message-type",
        )

        assertEquals(conflicts, fixtures.filter { classify(it) == CoverageStatus.EVIDENCE_LAYOUT_CONFLICT }.map { it.id }.toSet())
    }

    private fun classify(fixture: CompatibilityFixture): CoverageStatus = when {
        fixture.id in IMPLEMENT_NOW -> CoverageStatus.IMPLEMENT_NOW
        fixture.id in EVIDENCE_LAYOUT_CONFLICTS -> CoverageStatus.EVIDENCE_LAYOUT_CONFLICT
        fixture.decisionState == FixtureDecisionState.BLOCKED_BY_PROTOCOL_DECISION -> CoverageStatus.BLOCKED
        fixture.category in setOf(FixtureCategory.NOISE, FixtureCategory.NOSTR, FixtureCategory.FRAGMENT, FixtureCategory.SYNC) -> CoverageStatus.LATER_PHASE
        else -> CoverageStatus.METADATA_ONLY
    }

    private fun resource(path: String): String = checkNotNull(checkNotNull(javaClass.classLoader).getResource(path)) {
        "Missing compatibility resource $path"
    }.readText()

    private enum class CoverageStatus {
        IMPLEMENT_NOW,
        BLOCKED,
        LATER_PHASE,
        METADATA_ONLY,
        EVIDENCE_LAYOUT_CONFLICT,
    }

    private companion object {
        val IMPLEMENT_NOW = setOf(
            "apple-v1-broadcast", "apple-v1-recipient", "apple-v2-broadcast", "apple-v2-recipient", "apple-v2-route",
            "apple-announce-legacy", "apple-announce-extended",
            "android-v1-broadcast", "android-v1-recipient", "android-v2-broadcast", "android-v2-recipient", "android-v2-route",
            "android-announce-legacy", "android-announce-extended",
            "malformed-empty-input", "malformed-truncated-header", "malformed-unsupported-version", "malformed-truncated-sender",
            "malformed-truncated-recipient", "malformed-invalid-route-length", "malformed-payload-length-mismatch",
            "malformed-oversized-advertised-payload", "malformed-duplicate-tlv",
        )

        val EVIDENCE_LAYOUT_CONFLICTS = setOf(
            "malformed-compression-size-abuse",
            "malformed-suspicious-compression-ratio",
            "malformed-truncated-signature",
            "malformed-invalid-padding",
            "malformed-unknown-message-type",
        )
    }
}
