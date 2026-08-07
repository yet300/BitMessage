package com.yet.bitmessage.testing.compatibility

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompatibilityCorpusTest {
    private val manifest by lazy {
        FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json"))
    }

    @Test
    fun corpusIsStructurallyValidAndProfilePinned() {
        assertEquals("BitchatBaseline2026_08", manifest.profile)
        assertEquals(emptyList(), FixtureManifestParser.validate(manifest))
    }

    @Test
    fun everyCompatibilitySignificantFixtureFieldIsHashLocked() {
        val mismatches = manifest.fixtures.mapNotNull { fixture ->
            val canonical = FixtureCanonicalForm.encode(manifest.formatVersion, manifest.profile, fixture)
            val actual = canonical.sha256()
            if (actual == fixture.contentSha256) null else "${fixture.id}: expected ${fixture.contentSha256}, actual $actual"
        }

        assertEquals(emptyList(), mismatches)
        assertEquals(manifest.fixtures.size, manifest.fixtures.map(CompatibilityFixture::contentSha256).toSet().size)
    }

    @Test
    fun dualUpstreamCorpusHasIndependentOriginsAndReciprocalAcceptance() {
        val accepted = manifest.fixtures.filter { it.expectedOutcome.status == FixtureOutcomeStatus.ACCEPT }
        assertEquals(14, accepted.size)
        assertEquals(7, accepted.count { it.provenance.producer == FixtureProducer.APPLE })
        assertEquals(7, accepted.count { it.provenance.producer == FixtureProducer.ANDROID })
        accepted.forEach { fixture ->
            assertEquals(setOf(UpstreamClient.APPLE, UpstreamClient.ANDROID), fixture.provenance.acceptedBy.toSet(), fixture.id)
        }
    }

    @Test
    fun corpusHasRequiredLiteralAndHostileFamilies() {
        assertEquals(46, manifest.fixtures.size)
        assertEquals(
            mapOf(
                FixtureCategory.ANNOUNCEMENT to 6,
                FixtureCategory.CAPABILITIES to 3,
                FixtureCategory.COMPRESSION to 4,
                FixtureCategory.FRAGMENT to 2,
                FixtureCategory.MALFORMED to 13,
                FixtureCategory.NOISE to 4,
                FixtureCategory.NOSTR to 1,
                FixtureCategory.OUTER_PACKET to 10,
                FixtureCategory.SIGNING to 1,
                FixtureCategory.SYNC to 2,
            ),
            manifest.fixtures.groupingBy(CompatibilityFixture::category).eachCount(),
        )

        val requiredIds = setOf(
            "apple-v1-broadcast", "apple-v1-recipient", "apple-v2-broadcast", "apple-v2-recipient", "apple-v2-route",
            "apple-announce-legacy", "apple-announce-extended",
            "android-v1-broadcast", "android-v1-recipient", "android-v2-broadcast", "android-v2-recipient", "android-v2-route",
            "android-announce-legacy", "android-announce-extended",
            "malformed-empty-input", "malformed-truncated-header", "malformed-unsupported-version", "malformed-truncated-sender",
            "malformed-truncated-recipient", "malformed-invalid-route-length", "malformed-oversized-route",
            "malformed-payload-length-mismatch", "malformed-oversized-advertised-payload", "malformed-invalid-padding",
            "malformed-compression-size-abuse", "malformed-suspicious-compression-ratio", "malformed-truncated-signature",
            "malformed-unknown-message-type", "drift-unknown-tlv-preservation", "drift-unknown-capability-preservation",
            "malformed-duplicate-tlv", "malformed-fragment-metadata-mismatch", "malformed-fragment-count-abuse",
            "malformed-sync-payload", "malformed-noise-payload", "malformed-nostr-envelope",
            "drift-announce-v2-0x2c", "drift-authenticated-peer-state-0x21", "drift-peer-id-short", "drift-peer-id-long",
            "drift-foreign-compressed-representation", "drift-decompression-cap-boundary", "drift-neighbor-encoding-ambiguity",
            "drift-gcs-signed-unsigned", "drift-noise-message-three-48", "drift-noise-message-three-64",
        )
        assertEquals(requiredIds, manifest.fixtures.map(CompatibilityFixture::id).toSet())
    }

    @Test
    fun unresolvedDriftCannotSilentlyDefineShippingSemantics() {
        val rejected = manifest.fixtures.filter { it.expectedOutcome.status == FixtureOutcomeStatus.REJECT }
        assertEquals(17, rejected.size)

        val blocked = manifest.fixtures.filter { it.decisionState == FixtureDecisionState.BLOCKED_BY_PROTOCOL_DECISION }
        assertEquals(15, blocked.size)
        blocked.forEach { fixture ->
            assertEquals(FixtureOutcomeStatus.DECODE_ONLY, fixture.expectedOutcome.status, fixture.id)
            assertTrue(!fixture.blockedReason.isNullOrBlank(), fixture.id)
        }
    }

    @Test
    fun deferredSecurityAndIdentityScenariosRemainMandatoryInventory() {
        val scenarios = RegressionScenarioParser.parse(resource("BitchatBaseline2026_08/regression-scenarios.json"))
        assertEquals(5, scenarios.scenarios.size)
        assertEquals(
            setOf(
                "dedup-authentication-before-authority-mutation",
                "identity-peer-id-change-same-authenticated-identity",
                "identity-reconnect-new-link-same-peer",
                "identity-multiple-links-one-authenticated-identity",
                "identity-future-static-key-rotation",
            ),
            scenarios.scenarios.map(RegressionScenario::id).toSet(),
        )
        assertTrue(scenarios.scenarios.all { it.status == RegressionScenarioStatus.DEFERRED_UNTIL_PRODUCTION_COMPONENT_EXISTS })
    }

    private fun resource(path: String): String = checkNotNull(checkNotNull(javaClass.classLoader).getResource(path)) {
        "Missing compatibility resource $path"
    }.readText()

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
