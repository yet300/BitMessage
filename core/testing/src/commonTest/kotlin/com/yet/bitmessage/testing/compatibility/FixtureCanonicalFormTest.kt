package com.yet.bitmessage.testing.compatibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FixtureCanonicalFormTest {

    @Test
    fun compatibilitySignificantChangesAlterCanonicalContent() {
        val original = fixture()
        val originalCanonical = FixtureCanonicalForm.encode(1, PROFILE, original)
        val variants = listOf(
            original.copy(wireBytesHex = "010101"),
            original.copy(expectedOutcome = FixtureOutcome(FixtureOutcomeStatus.DECODE_ONLY)),
            original.copy(signingTranscriptHex = "01"),
            original.copy(signingTranscriptSha256 = "1".repeat(64)),
            original.copy(securityLimits = listOf(SecurityLimit("MAX_PACKET_BYTES", "512"))),
            original.copy(provenance = original.provenance.copy(sourceTest = "differentProducerTest")),
            original.copy(direction = FixtureDirection.BIDIRECTIONAL),
            original.copy(decisionState = FixtureDecisionState.BLOCKED_BY_PROTOCOL_DECISION, blockedReason = "Needs decision"),
        )

        variants.forEach { variant ->
            assertNotEquals(originalCanonical, FixtureCanonicalForm.encode(1, PROFILE, variant))
        }
        assertNotEquals(originalCanonical, FixtureCanonicalForm.encode(1, "DifferentProfile", original))
        assertNotEquals(originalCanonical, FixtureCanonicalForm.encode(2, PROFILE, original))
    }

    @Test
    fun descriptiveChangesDoNotAlterCanonicalContent() {
        val original = fixture()
        val descriptiveEdit = original.copy(description = "Clearer prose", notes = "More context")

        assertEquals(
            FixtureCanonicalForm.encode(1, PROFILE, original),
            FixtureCanonicalForm.encode(1, PROFILE, descriptiveEdit),
        )
    }

    @Test
    fun fieldOrderingDoesNotAlterCanonicalContent() {
        val original = fixture().copy(
            semanticFields = listOf(SemanticField("z", "last"), SemanticField("a", "first")),
            securityLimits = listOf(SecurityLimit("Z_LIMIT", "2"), SecurityLimit("A_LIMIT", "1")),
        )
        val reordered = original.copy(
            semanticFields = original.semanticFields.reversed(),
            securityLimits = original.securityLimits.reversed(),
        )

        assertEquals(
            FixtureCanonicalForm.encode(1, PROFILE, original),
            FixtureCanonicalForm.encode(1, PROFILE, reordered),
        )
    }

    private fun fixture() = CompatibilityFixture(
        id = "apple-v1-broadcast",
        description = "Pinned Apple unpadded v1 broadcast",
        category = FixtureCategory.OUTER_PACKET,
        direction = FixtureDirection.APPLE_TO_ANDROID,
        provenance = FixtureProvenance(
            sourceRepository = "permissionlesstech/bitchat",
            sourceCommitSha = "1f59e814f90c3f489f48d68262cb1bf640bf6181",
            sourcePath = "BinaryProtocolTests.swift",
            sourceTest = "basicPacketEncodingDecoding",
            producer = FixtureProducer.APPLE,
            acceptedBy = listOf(UpstreamClient.APPLE, UpstreamClient.ANDROID),
        ),
        wireBytesHex = "010100",
        expectedOutcome = FixtureOutcome(FixtureOutcomeStatus.ACCEPT),
        semanticFields = listOf(SemanticField("version", "1")),
        securityLimits = listOf(SecurityLimit("MAX_PACKET_BYTES", "1048576")),
        decisionState = FixtureDecisionState.RESOLVED,
        contentSha256 = "0".repeat(64),
        notes = "Original note",
    )

    private companion object {
        const val PROFILE = "BitchatBaseline2026_08"
    }
}
