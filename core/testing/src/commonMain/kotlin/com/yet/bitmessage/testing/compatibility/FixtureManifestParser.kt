package com.yet.bitmessage.testing.compatibility

import kotlinx.serialization.json.Json

object FixtureManifestParser {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(value: String): FixtureManifest = json.decodeFromString(value)

    fun validate(manifest: FixtureManifest): List<FixtureValidationIssue> = buildList {
        if (manifest.formatVersion != 1) {
            add(issue(null, FixtureValidationCode.UNSUPPORTED_FORMAT_VERSION, "Expected format version 1"))
        }
        if (manifest.profile.isBlank()) {
            add(issue(null, FixtureValidationCode.BLANK_PROFILE, "Compatibility profile is required"))
        }

        val duplicateIds = manifest.fixtures
            .groupingBy(CompatibilityFixture::id)
            .eachCount()
            .filterValues { it > 1 }
            .keys

        manifest.fixtures.forEach { fixture ->
            if (fixture.id.isBlank()) {
                add(issue(fixture.id, FixtureValidationCode.BLANK_FIXTURE_ID, "Fixture ID is required"))
            }
            if (fixture.id in duplicateIds) {
                add(issue(fixture.id, FixtureValidationCode.DUPLICATE_FIXTURE_ID, "Fixture ID must be unique"))
            }
            if (!fixture.provenance.sourceCommitSha.matches(SHA1)) {
                add(issue(fixture.id, FixtureValidationCode.INVALID_SOURCE_COMMIT, "Source commit must be a lowercase full SHA-1"))
            }
            if (fixture.provenance.sourceRepository.isBlank()) {
                add(issue(fixture.id, FixtureValidationCode.BLANK_SOURCE_REPOSITORY, "Source repository is required"))
            }
            if (fixture.provenance.sourcePath.isBlank()) {
                add(issue(fixture.id, FixtureValidationCode.BLANK_SOURCE_PATH, "Source path is required"))
            }
            if (fixture.provenance.sourceTest.isBlank()) {
                add(issue(fixture.id, FixtureValidationCode.BLANK_SOURCE_TEST, "Source test or derivation is required"))
            }
            val isEmptyInputCase = fixture.expectedOutcome.status == FixtureOutcomeStatus.REJECT &&
                fixture.expectedOutcome.rejectCode == FixtureRejectCode.EMPTY_INPUT
            if ((!isEmptyInputCase && fixture.wireBytesHex.isEmpty()) ||
                (fixture.wireBytesHex.isNotEmpty() && !fixture.wireBytesHex.isEvenHex())
            ) {
                add(issue(fixture.id, FixtureValidationCode.INVALID_WIRE_HEX, "Wire bytes must be lowercase even-length hexadecimal; only EMPTY_INPUT may be empty"))
            }
            if (fixture.signingTranscriptHex != null && !fixture.signingTranscriptHex.isEvenHex()) {
                add(issue(fixture.id, FixtureValidationCode.INVALID_SIGNING_TRANSCRIPT_HEX, "Signing transcript must be lowercase hexadecimal"))
            }
            if (fixture.signingTranscriptSha256 != null && !fixture.signingTranscriptSha256.matches(SHA256)) {
                add(issue(fixture.id, FixtureValidationCode.INVALID_SIGNING_TRANSCRIPT_HASH, "Signing transcript hash must be lowercase SHA-256"))
            }
            if ((fixture.signingTranscriptHex == null) != (fixture.signingTranscriptSha256 == null)) {
                add(issue(fixture.id, FixtureValidationCode.SIGNING_TRANSCRIPT_HASH_MISMATCH, "Signing transcript and its hash must be present together"))
            }
            if (!fixture.contentSha256.matches(SHA256)) {
                add(issue(fixture.id, FixtureValidationCode.INVALID_CONTENT_HASH, "Content hash must be lowercase SHA-256"))
            }

            when (fixture.expectedOutcome.status) {
                FixtureOutcomeStatus.REJECT -> if (fixture.expectedOutcome.rejectCode == null) {
                    add(issue(fixture.id, FixtureValidationCode.REJECT_CODE_REQUIRED, "Rejected fixtures require a typed reject code"))
                }
                FixtureOutcomeStatus.ACCEPT,
                FixtureOutcomeStatus.DECODE_ONLY,
                -> if (fixture.expectedOutcome.rejectCode != null) {
                    add(issue(fixture.id, FixtureValidationCode.REJECT_CODE_FOR_NON_REJECT, "Only rejected fixtures may carry a reject code"))
                }
            }

            when (fixture.decisionState) {
                FixtureDecisionState.BLOCKED_BY_PROTOCOL_DECISION -> if (fixture.blockedReason.isNullOrBlank()) {
                    add(issue(fixture.id, FixtureValidationCode.BLOCKED_REASON_REQUIRED, "Blocked fixtures require a decision reason"))
                }
                FixtureDecisionState.RESOLVED -> if (fixture.blockedReason != null) {
                    add(issue(fixture.id, FixtureValidationCode.BLOCKED_REASON_FOR_RESOLVED, "Resolved fixtures cannot carry a blocked reason"))
                }
            }

            if (fixture.semanticFields.hasDuplicateSemanticIds()) {
                add(issue(fixture.id, FixtureValidationCode.DUPLICATE_SEMANTIC_FIELD, "Semantic field IDs must be unique"))
            }
            if (fixture.securityLimits.hasDuplicateLimitIds()) {
                add(issue(fixture.id, FixtureValidationCode.DUPLICATE_SECURITY_LIMIT, "Security limit IDs must be unique"))
            }
        }
    }

    private fun String.isEvenHex(): Boolean = length % 2 == 0 && matches(LOWER_HEX)

    private fun List<SemanticField>.hasDuplicateSemanticIds(): Boolean = map(SemanticField::id).distinct().size != size

    private fun List<SecurityLimit>.hasDuplicateLimitIds(): Boolean = map(SecurityLimit::id).distinct().size != size

    private fun issue(
        fixtureId: String?,
        code: FixtureValidationCode,
        detail: String,
    ) = FixtureValidationIssue(fixtureId, code, detail)

    private val LOWER_HEX = Regex("[0-9a-f]+")
    private val SHA1 = Regex("[0-9a-f]{40}")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
