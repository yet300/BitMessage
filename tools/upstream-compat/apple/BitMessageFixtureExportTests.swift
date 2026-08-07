import Foundation
import Testing
@testable import BitFoundation

struct BitMessageFixtureExportTests {
    @Test
    func exportDeterministicOuterPackets() throws {
        let sender = try #require(Data(hexString: "0011223344556677"))
        let recipient = try #require(Data(hexString: "8899aabbccddeeff"))
        let timestamp: UInt64 = 0x0102_0304_0506_0708
        let payload = Data([0x41, 0x42])

        let fixtures: [(String, BitchatPacket)] = [
            ("apple-v1-broadcast", BitchatPacket(type: 0x02, senderID: sender, recipientID: nil, timestamp: timestamp, payload: payload, signature: nil, ttl: 3, version: 1)),
            ("apple-v1-recipient", BitchatPacket(type: 0x02, senderID: sender, recipientID: recipient, timestamp: timestamp, payload: payload, signature: nil, ttl: 3, version: 1)),
            ("apple-v2-broadcast", BitchatPacket(type: 0x02, senderID: sender, recipientID: nil, timestamp: timestamp, payload: payload, signature: nil, ttl: 3, version: 2)),
            ("apple-v2-recipient", BitchatPacket(type: 0x02, senderID: sender, recipientID: recipient, timestamp: timestamp, payload: payload, signature: nil, ttl: 3, version: 2)),
            ("apple-v2-route", BitchatPacket(type: 0x02, senderID: sender, recipientID: recipient, timestamp: timestamp, payload: payload, signature: nil, ttl: 3, version: 2, route: [sender, recipient])),
        ]

        for (id, packet) in fixtures {
            let encoded = try #require(BinaryProtocol.encode(packet, padding: false))
            print("BITMESSAGE_FIXTURE \(id) \(encoded.map { String(format: "%02x", $0) }.joined())")
        }
    }
}
