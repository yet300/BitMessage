import Foundation
import Testing
@testable import BitFoundation

struct BitMessageFixtureAcceptanceTests {
    @Test
    func acceptsPinnedAndroidOuterPackets() throws {
        let literals = [
            "010203010203040506070800000200112233445566774142",
            "010203010203040506070801000200112233445566778899aabbccddeeff4142",
            "0202030102030405060708000000000200112233445566774142",
            "0202030102030405060708010000000200112233445566778899aabbccddeeff4142",
            "0202030102030405060708090000000200112233445566778899aabbccddeeff0200112233445566778899aabbccddeeff4142",
        ]
        for literal in literals {
            let bytes = try #require(Data(hexString: literal))
            #expect(BinaryProtocol.decode(bytes) != nil)
        }
    }
}
