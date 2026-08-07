package com.yet.bitmessage.testing.compatibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class FixtureManifestParserTest {

    @Test
    fun validManifestParsesWithoutValidationIssues() {
        val manifest = FixtureManifestParser.parse(validManifestJson())

        assertEquals(1, manifest.formatVersion)
        assertEquals("BitchatBaseline2026_08", manifest.profile)
        assertEquals("apple-v1-broadcast", manifest.fixtures.single().id)
        assertTrue(FixtureManifestParser.validate(manifest).isEmpty())
    }

    @Test
    fun missingRequiredProvenanceFailsParsing() {
        val invalid = validManifestJson().replace(
            "\"sourcePath\":\"localPackages/BitFoundation/Tests/BitFoundationTests/BinaryProtocolTests.swift\",",
            "",
        )

        assertFails { FixtureManifestParser.parse(invalid) }
    }

    @Test
    fun missingRequiredNotesFailsParsing() {
        val invalid = validManifestJson().replace(",\n  \"notes\":\"Parser test fixture\"", "")

        assertFails { FixtureManifestParser.parse(invalid) }
    }

    @Test
    fun duplicateFixtureIdsAreRejected() {
        val fixture = validFixtureJson()
        val manifest = FixtureManifestParser.parse(manifestJson("$fixture,$fixture"))

        assertTrue(
            FixtureManifestParser.validate(manifest).any {
                it.code == FixtureValidationCode.DUPLICATE_FIXTURE_ID
            },
        )
    }

    @Test
    fun wireBytesMustBeEvenLengthLowercaseHex() {
        val invalid = FixtureManifestParser.parse(
            manifestJson(validFixtureJson().replace("010100", "abc")),
        )

        assertTrue(
            FixtureManifestParser.validate(invalid).any {
                it.code == FixtureValidationCode.INVALID_WIRE_HEX
            },
        )
    }

    @Test
    fun emptyWireBytesAreAllowedOnlyForTypedEmptyInputRejection() {
        val emptyInput = FixtureManifestParser.parse(
            manifestJson(
                validFixtureJson()
                    .replace("\"wireBytesHex\":\"010100\"", "\"wireBytesHex\":\"\"")
                    .replace("\"status\":\"ACCEPT\"", "\"status\":\"REJECT\",\"rejectCode\":\"EMPTY_INPUT\""),
            ),
        )

        assertTrue(FixtureManifestParser.validate(emptyInput).isEmpty())
    }

    @Test
    fun rejectOutcomeRequiresTypedRejectCode() {
        val invalid = FixtureManifestParser.parse(
            manifestJson(
                validFixtureJson()
                    .replace("\"status\":\"ACCEPT\"", "\"status\":\"REJECT\""),
            ),
        )

        assertTrue(
            FixtureManifestParser.validate(invalid).any {
                it.code == FixtureValidationCode.REJECT_CODE_REQUIRED
            },
        )
    }

    @Test
    fun acceptOutcomeCannotCarryRejectCode() {
        val invalid = FixtureManifestParser.parse(
            manifestJson(
                validFixtureJson().replace(
                    "\"status\":\"ACCEPT\"",
                    "\"status\":\"ACCEPT\",\"rejectCode\":\"TRUNCATED_HEADER\"",
                ),
            ),
        )

        assertTrue(
            FixtureManifestParser.validate(invalid).any {
                it.code == FixtureValidationCode.REJECT_CODE_FOR_NON_REJECT
            },
        )
    }

    @Test
    fun blockedDecisionRequiresReason() {
        val invalid = FixtureManifestParser.parse(
            manifestJson(
                validFixtureJson().replace(
                    "\"decisionState\":\"RESOLVED\"",
                    "\"decisionState\":\"BLOCKED_BY_PROTOCOL_DECISION\"",
                ),
            ),
        )

        assertTrue(
            FixtureManifestParser.validate(invalid).any {
                it.code == FixtureValidationCode.BLOCKED_REASON_REQUIRED
            },
        )
    }

    @Test
    fun contentHashMustBeLowercaseSha256() {
        val invalid = FixtureManifestParser.parse(
            manifestJson(validFixtureJson().replace("0".repeat(64), "ABC123")),
        )

        assertTrue(
            FixtureManifestParser.validate(invalid).any {
                it.code == FixtureValidationCode.INVALID_CONTENT_HASH
            },
        )
    }

    @Test
    fun signingTranscriptAndHashMustBePresentTogether() {
        val invalid = FixtureManifestParser.parse(
            manifestJson(
                validFixtureJson().replace(
                    "\"contentSha256\"",
                    "\"signingTranscriptHex\":\"00\",\n          \"contentSha256\"",
                ),
            ),
        )

        assertTrue(
            FixtureManifestParser.validate(invalid).any {
                it.code == FixtureValidationCode.SIGNING_TRANSCRIPT_HASH_MISMATCH
            },
        )
    }

    private fun validManifestJson(): String = manifestJson(validFixtureJson())

    private fun manifestJson(fixtures: String): String =
        """{"formatVersion":1,"profile":"BitchatBaseline2026_08","fixtures":[$fixtures]}"""

    private fun validFixtureJson(): String =
        """
        {
          "id":"apple-v1-broadcast",
          "description":"Pinned Apple unpadded v1 broadcast",
          "category":"OUTER_PACKET",
          "direction":"APPLE_TO_ANDROID",
          "provenance":{
            "sourceRepository":"permissionlesstech/bitchat",
            "sourceCommitSha":"1f59e814f90c3f489f48d68262cb1bf640bf6181",
            "sourcePath":"localPackages/BitFoundation/Tests/BitFoundationTests/BinaryProtocolTests.swift",
            "sourceTest":"basicPacketEncodingDecoding",
            "producer":"APPLE",
            "acceptedBy":["APPLE","ANDROID"]
          },
          "wireBytesHex":"010100",
          "expectedOutcome":{"status":"ACCEPT"},
          "semanticFields":[{"id":"version","value":"1"}],
          "securityLimits":[{"id":"MAX_PACKET_BYTES","value":"1048576"}],
          "decisionState":"RESOLVED",
          "contentSha256":"${"0".repeat(64)}",
          "notes":"Parser test fixture"
        }
        """.trimIndent()
}
