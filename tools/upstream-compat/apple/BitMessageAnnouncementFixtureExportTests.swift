import BitFoundation
import Foundation
import Testing
@testable import bitchat

struct BitMessageAnnouncementFixtureExportTests {
    @Test
    func exportDeterministicAnnouncements() throws {
        let legacy = AnnouncementPacket(
            nickname: "peer",
            noisePublicKey: Data(repeating: 0x11, count: 32),
            signingPublicKey: Data(repeating: 0x22, count: 32),
            directNeighbors: nil
        )
        let extended = AnnouncementPacket(
            nickname: "peer",
            noisePublicKey: Data(repeating: 0x11, count: 32),
            signingPublicKey: Data(repeating: 0x22, count: 32),
            directNeighbors: [Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])],
            capabilities: PeerCapabilities(rawValue: 0x8100)
        )

        let fixtures = [("apple-announce-legacy", legacy), ("apple-announce-extended", extended)]
        let lines = try fixtures.map { id, packet in
            let encoded = try #require(packet.encode())
            return "BITMESSAGE_FIXTURE \(id) \(encoded.map { String(format: "%02x", $0) }.joined())"
        }
        try lines.joined(separator: "\n").write(
            to: URL(fileURLWithPath: "/tmp/bitmessage-phase1-apple-announcement-fixtures.txt"),
            atomically: true,
            encoding: .utf8
        )
    }
}
