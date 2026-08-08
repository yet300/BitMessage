package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.payload.AnnouncementCodec
import com.yet.bitmessage.testing.compatibility.CompatibilityFixture
import com.yet.bitmessage.testing.compatibility.FixtureCategory
import com.yet.bitmessage.testing.compatibility.FixtureDecisionState
import com.yet.bitmessage.testing.compatibility.FixtureManifest
import com.yet.bitmessage.testing.compatibility.FixtureOutcomeStatus
import java.io.File

internal object ProductionFixtureCoverage {
    fun create(manifest: FixtureManifest): ProductionFixtureReport {
        val entries = manifest.fixtures.map { fixture ->
            val status = classify(fixture)
            if (status == ProductionCoverageStatus.EXECUTED) verifyProductionResult(fixture)
            ProductionFixtureCoverageEntry(fixture.id, status)
        }
        return ProductionFixtureReport(manifest.profile, entries)
    }

    fun write(report: ProductionFixtureReport, output: File) {
        output.parentFile?.mkdirs()
        output.writeText(
            buildString {
                append("{\n  \"profile\": \"")
                append(report.profile)
                append("\",\n  \"entries\": [\n")
                report.entries.forEachIndexed { index, entry ->
                    append("    {\"fixtureId\": \"")
                    append(entry.fixtureId)
                    append("\", \"status\": \"")
                    append(entry.status.name)
                    append("\"}")
                    if (index != report.entries.lastIndex) append(',')
                    append('\n')
                }
                append("  ]\n}\n")
            },
        )
    }

    private fun classify(fixture: CompatibilityFixture): ProductionCoverageStatus = when {
        fixture.id in EXECUTED_FIXTURES -> ProductionCoverageStatus.EXECUTED
        fixture.id in EVIDENCE_LAYOUT_CONFLICTS -> ProductionCoverageStatus.EVIDENCE_LAYOUT_CONFLICT
        fixture.decisionState == FixtureDecisionState.BLOCKED_BY_PROTOCOL_DECISION -> ProductionCoverageStatus.BLOCKED
        fixture.category in LATER_PHASE_CATEGORIES -> ProductionCoverageStatus.LATER_PHASE
        else -> ProductionCoverageStatus.METADATA_ONLY
    }

    private fun verifyProductionResult(fixture: CompatibilityFixture) {
        val result =
            if (fixture.id == "malformed-duplicate-tlv" || fixture.category in ANNOUNCEMENT_CATEGORIES) {
                AnnouncementCodec.decode(bytes(fixture.wireBytesHex))
            } else {
                BitchatCodec.decode(bytes(fixture.wireBytesHex))
            }
        when (fixture.expectedOutcome.status) {
            FixtureOutcomeStatus.ACCEPT -> check(result is DecodeResult.Success<*>) {
                "Expected ${fixture.id} to decode, but got $result."
            }
            FixtureOutcomeStatus.REJECT -> check(result == DecodeResult.Failure(EXECUTED_REJECTION_ERRORS.getValue(fixture.id))) {
                "Expected ${fixture.id} to have its resolved typed rejection, but got $result."
            }
            FixtureOutcomeStatus.DECODE_ONLY -> error("Decode-only fixture ${fixture.id} cannot be executed as production behavior.")
        }
    }

    private fun bytes(hex: String): Bytes =
        Bytes.copyOf(
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            },
        )

    private val EXECUTED_FIXTURES = setOf(
        "apple-v1-broadcast", "apple-v1-recipient", "apple-v2-broadcast", "apple-v2-recipient", "apple-v2-route",
        "apple-announce-legacy", "apple-announce-extended",
        "android-v1-broadcast", "android-v1-recipient", "android-v2-broadcast", "android-v2-recipient", "android-v2-route",
        "android-announce-legacy", "android-announce-extended",
        "malformed-empty-input", "malformed-truncated-header", "malformed-unsupported-version", "malformed-truncated-sender",
        "malformed-truncated-recipient", "malformed-invalid-route-length", "malformed-payload-length-mismatch",
        "malformed-oversized-advertised-payload", "malformed-duplicate-tlv",
    )

    private val EXECUTED_REJECTION_ERRORS = mapOf(
        "malformed-empty-input" to DecodeError.EMPTY_INPUT,
        "malformed-truncated-header" to DecodeError.TRUNCATED,
        "malformed-unsupported-version" to DecodeError.UNSUPPORTED_VERSION,
        "malformed-truncated-sender" to DecodeError.TRUNCATED,
        "malformed-truncated-recipient" to DecodeError.TRUNCATED,
        "malformed-invalid-route-length" to DecodeError.INVALID_LENGTH,
        "malformed-payload-length-mismatch" to DecodeError.INVALID_LENGTH,
        "malformed-oversized-advertised-payload" to DecodeError.LIMIT_EXCEEDED,
        "malformed-duplicate-tlv" to DecodeError.MALFORMED_FIELD,
    )

    private val EVIDENCE_LAYOUT_CONFLICTS = setOf(
        "malformed-compression-size-abuse",
        "malformed-suspicious-compression-ratio",
        "malformed-truncated-signature",
        "malformed-invalid-padding",
        "malformed-unknown-message-type",
    )

    private val LATER_PHASE_CATEGORIES = setOf(
        FixtureCategory.NOISE,
        FixtureCategory.NOSTR,
        FixtureCategory.FRAGMENT,
        FixtureCategory.SYNC,
    )

    private val ANNOUNCEMENT_CATEGORIES = setOf(
        FixtureCategory.ANNOUNCEMENT,
        FixtureCategory.CAPABILITIES,
    )
}

internal data class ProductionFixtureReport(
    val profile: String,
    val entries: List<ProductionFixtureCoverageEntry>,
)

internal data class ProductionFixtureCoverageEntry(
    val fixtureId: String,
    val status: ProductionCoverageStatus,
)

internal enum class ProductionCoverageStatus {
    EXECUTED,
    METADATA_ONLY,
    BLOCKED,
    LATER_PHASE,
    NOT_APPLICABLE,
    EVIDENCE_LAYOUT_CONFLICT,
}
