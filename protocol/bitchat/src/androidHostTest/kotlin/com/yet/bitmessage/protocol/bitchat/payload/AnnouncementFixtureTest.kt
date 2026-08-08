package com.yet.bitmessage.protocol.bitchat.payload

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.protocol.bitchat.DecodeResult
import com.yet.bitmessage.protocol.bitchat.EncodeResult
import com.yet.bitmessage.testing.compatibility.FixtureManifestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AnnouncementFixtureTest {
    @Test
    fun everyResolvedAnnouncementFixtureDecodesAndReencodesExactly() {
        val fixtures = FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json")).fixtures
            .filter { it.id in ANNOUNCEMENT_FIXTURE_IDS }

        assertEquals(ANNOUNCEMENT_FIXTURE_IDS, fixtures.map { it.id }.toSet())
        fixtures.forEach { fixture ->
            val wire = bytes(fixture.wireBytesHex)
            val decoded = assertIs<DecodeResult.Success<AnnouncementPayload>>(AnnouncementCodec.decode(wire)).value

            assertEquals(wire, decoded.rawPayload, fixture.id)
            assertEquals(wire, assertIs<EncodeResult.Success>(AnnouncementCodec.encode(decoded), fixture.id).bytes)
        }
    }

    private fun resource(path: String): String = checkNotNull(checkNotNull(javaClass.classLoader).getResource(path)) {
        "Missing compatibility resource $path"
    }.readText()

    private fun bytes(hex: String): Bytes =
        Bytes.copyOf(
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            },
        )

    private companion object {
        val ANNOUNCEMENT_FIXTURE_IDS = setOf(
            "apple-announce-legacy",
            "apple-announce-extended",
            "android-announce-legacy",
            "android-announce-extended",
        )
    }
}
