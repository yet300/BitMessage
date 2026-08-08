package com.yet.bitmessage.protocol.bitchat

import com.yet.bitmessage.foundation.Bytes
import com.yet.bitmessage.testing.compatibility.FixtureManifestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class V2FixtureTest {
    @Test
    fun everyResolvedV2OuterFixtureDecodesAndReencodesExactly() {
        val fixtures = FixtureManifestParser.parse(resource("BitchatBaseline2026_08/fixtures.json")).fixtures
            .filter { it.id in V2_FIXTURE_IDS }

        assertEquals(V2_FIXTURE_IDS, fixtures.map { it.id }.toSet())
        fixtures.forEach { fixture ->
            val wire = bytes(fixture.wireBytesHex)
            val decoded = assertIs<DecodeResult.Success<DecodedPacket>>(BitchatCodec.decode(wire)).value

            assertEquals(wire, decoded.rawPacket.wireBytes, fixture.id)
            assertEquals(wire, assertIs<EncodeResult.Success>(BitchatCodec.encode(decoded), fixture.id).bytes)
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
        val V2_FIXTURE_IDS = setOf(
            "apple-v2-broadcast",
            "apple-v2-recipient",
            "apple-v2-route",
            "android-v2-broadcast",
            "android-v2-recipient",
            "android-v2-route",
        )
    }
}
