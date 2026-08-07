package com.yet.bitmessage.testing.compatibility

import kotlinx.serialization.Serializable

@Serializable
data class FixtureManifest(
    val formatVersion: Int,
    val profile: String,
    val fixtures: List<CompatibilityFixture>,
)

@Serializable
data class CompatibilityFixture(
    val id: String,
    val description: String,
    val category: FixtureCategory,
    val direction: FixtureDirection,
    val provenance: FixtureProvenance,
    val wireBytesHex: String,
    val expectedOutcome: FixtureOutcome,
    val semanticFields: List<SemanticField> = emptyList(),
    val signingTranscriptHex: String? = null,
    val signingTranscriptSha256: String? = null,
    val securityLimits: List<SecurityLimit> = emptyList(),
    val decisionState: FixtureDecisionState = FixtureDecisionState.RESOLVED,
    val blockedReason: String? = null,
    val historicalProvenance: HistoricalProvenance? = null,
    val contentSha256: String,
    val notes: String,
)

@Serializable
data class FixtureProvenance(
    val sourceRepository: String,
    val sourceCommitSha: String,
    val sourcePath: String,
    val sourceTest: String,
    val producer: FixtureProducer,
    val acceptedBy: List<UpstreamClient> = emptyList(),
)

@Serializable
data class HistoricalProvenance(
    val sourceRepository: String,
    val sourceCommitSha: String,
    val sourcePath: String,
    val sourceTest: String,
    val promotionDecision: HistoricalPromotionDecision,
)

@Serializable
data class FixtureOutcome(
    val status: FixtureOutcomeStatus,
    val rejectCode: FixtureRejectCode? = null,
)

@Serializable
data class SemanticField(
    val id: String,
    val value: String,
)

@Serializable
data class SecurityLimit(
    val id: String,
    val value: String,
)

@Serializable
enum class FixtureCategory {
    OUTER_PACKET,
    ANNOUNCEMENT,
    CAPABILITIES,
    SIGNING,
    COMPRESSION,
    FRAGMENT,
    SYNC,
    NOISE,
    NOSTR,
    MALFORMED,
}

@Serializable
enum class FixtureDirection {
    APPLE_TO_ANDROID,
    ANDROID_TO_APPLE,
    BIDIRECTIONAL,
    DECODER_INPUT,
}

@Serializable
enum class FixtureProducer {
    APPLE,
    ANDROID,
    INDEPENDENT_AUDIT,
}

@Serializable
enum class UpstreamClient {
    APPLE,
    ANDROID,
}

@Serializable
enum class FixtureOutcomeStatus {
    ACCEPT,
    REJECT,
    DECODE_ONLY,
}

@Serializable
enum class FixtureDecisionState {
    RESOLVED,
    BLOCKED_BY_PROTOCOL_DECISION,
}

@Serializable
enum class HistoricalPromotionDecision {
    PROMOTED_AFTER_CURRENT_VALIDATION,
    REJECTED,
}

@Serializable
enum class FixtureRejectCode {
    EMPTY_INPUT,
    TRUNCATED_HEADER,
    UNSUPPORTED_VERSION,
    TRUNCATED_SENDER,
    TRUNCATED_RECIPIENT,
    INVALID_ROUTE_LENGTH,
    ROUTE_LIMIT_EXCEEDED,
    PAYLOAD_LENGTH_MISMATCH,
    PAYLOAD_LIMIT_EXCEEDED,
    INVALID_PADDING,
    DECOMPRESSION_LIMIT_EXCEEDED,
    SUSPICIOUS_COMPRESSION_RATIO,
    TRUNCATED_SIGNATURE,
    UNKNOWN_MESSAGE_TYPE,
    MALFORMED_TLV,
    DUPLICATE_TLV,
    INVALID_PEER_ID_LENGTH,
    FRAGMENT_METADATA_MISMATCH,
    FRAGMENT_COUNT_LIMIT_EXCEEDED,
    MALFORMED_SYNC_PAYLOAD,
    MALFORMED_NOISE_PAYLOAD,
    MALFORMED_NOSTR_ENVELOPE,
}

data class FixtureValidationIssue(
    val fixtureId: String?,
    val code: FixtureValidationCode,
    val detail: String,
)

enum class FixtureValidationCode {
    UNSUPPORTED_FORMAT_VERSION,
    BLANK_PROFILE,
    BLANK_FIXTURE_ID,
    DUPLICATE_FIXTURE_ID,
    INVALID_SOURCE_COMMIT,
    BLANK_SOURCE_REPOSITORY,
    BLANK_SOURCE_PATH,
    BLANK_SOURCE_TEST,
    INVALID_WIRE_HEX,
    INVALID_SIGNING_TRANSCRIPT_HEX,
    INVALID_SIGNING_TRANSCRIPT_HASH,
    SIGNING_TRANSCRIPT_HASH_MISMATCH,
    INVALID_CONTENT_HASH,
    REJECT_CODE_REQUIRED,
    REJECT_CODE_FOR_NON_REJECT,
    BLOCKED_REASON_REQUIRED,
    BLOCKED_REASON_FOR_RESOLVED,
    DUPLICATE_SEMANTIC_FIELD,
    DUPLICATE_SECURITY_LIMIT,
}
