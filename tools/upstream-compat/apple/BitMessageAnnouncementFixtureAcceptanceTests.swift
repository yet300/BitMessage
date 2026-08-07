import Foundation
import Testing
@testable import bitchat

struct BitMessageAnnouncementFixtureAcceptanceTests {
    @Test
    func acceptsPinnedAndroidAnnouncements() throws {
        let literals = [
            "0104706565720220111111111111111111111111111111111111111111111111111111111111111103202222222222222222222222222222222222222222222222222222222222222222",
            "01047065657202201111111111111111111111111111111111111111111111111111111111111111032022222222222222222222222222222222222222222222222222222222222222220502008104080102030405060708",
        ]
        for literal in literals {
            let bytes = Data(stride(from: 0, to: literal.count, by: 2).map {
                UInt8(literal[literal.index(literal.startIndex, offsetBy: $0)..<literal.index(literal.startIndex, offsetBy: $0 + 2)], radix: 16)!
            })
            #expect(AnnouncementPacket.decode(from: bytes) != nil)
        }
    }
}
