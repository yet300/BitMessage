import BitFoundation
import CryptoKit
import Foundation
import Testing
@testable import bitchat

struct BitMessagePhase4EvidenceTests {
    @Test
    func reproducePacketIdentitySigningRelayAndFragments() throws {
        let sender = try #require(Data(hexString: "0011223344556677"))
        let timestamp: UInt64 = 0x0102_0304_0506_0708
        let payload = Data([0x41, 0x42])
        let packet = BitchatPacket(
            type: MessageType.message.rawValue,
            senderID: sender,
            recipientID: nil,
            timestamp: timestamp,
            payload: payload,
            signature: nil,
            ttl: 3,
            version: 2
        )

        var identityInput = Data([packet.type])
        identityInput.append(packet.senderID)
        identityInput.append(contentsOf: withUnsafeBytes(of: timestamp.bigEndian) { Data($0) })
        identityInput.append(packet.payload)
        let digest = Data(SHA256.hash(data: identityInput))
        let packetID = PacketIdUtil.computeId(packet)

        let signature = Data(repeating: 0x5a, count: 64)
        let signed = BitchatPacket(
            type: packet.type,
            senderID: sender,
            recipientID: nil,
            timestamp: timestamp,
            payload: payload,
            signature: signature,
            ttl: 7,
            version: 2
        )
        let signingTranscript = try #require(signed.toBinaryDataForSigning())
        let signedBytes = try #require(signed.toBinaryData(padding: false))
        var relayed = signed
        relayed.ttl = 6
        let relayedBytes = try #require(relayed.toBinaryData(padding: false))
        let decodedRelay = try #require(BinaryProtocol.decode(relayedBytes))
        let relayedTranscript = try #require(decodedRelay.toBinaryDataForSigning())

        let inner = try #require(Data(hexString: "0202030102030405060708000000000200112233445566774142"))
        let fragmentID = try #require(Data(hexString: "0001020304050607"))
        let firstPayload = fragmentPayload(fragmentID: fragmentID, index: 0, total: 2, data: Data(inner.prefix(13)))
        let secondPayload = fragmentPayload(fragmentID: fragmentID, index: 1, total: 2, data: Data(inner.dropFirst(13)))
        let firstHeader = try #require(BLEFragmentHeader(packet: fragmentPacket(payload: firstPayload, sender: sender, timestamp: timestamp)))
        let secondHeader = try #require(BLEFragmentHeader(packet: fragmentPacket(payload: secondPayload, sender: sender, timestamp: timestamp)))
        var buffer = BLEFragmentAssemblyBuffer()
        _ = buffer.append(secondHeader, maxInFlightAssemblies: 8)
        let completion = buffer.append(firstHeader, maxInFlightAssemblies: 8)
        let reassembled: Data
        if case let .complete(_, bytes, _) = completion {
            reassembled = bytes
        } else {
            reassembled = Data()
            Issue.record("Expected out-of-order fragments to complete")
        }

        emit("identity-input", identityInput)
        emit("sha256", digest)
        emit("packet-id", packetID)
        emit("signed-wire", signedBytes)
        emit("signing-transcript", signingTranscript)
        emit("relayed-wire", relayedBytes)
        emit("fragment-metadata", try #require(Data(hexString: "00010203040506070001000202aabb")))
        emit("fragment-zero", firstPayload)
        emit("fragment-one", secondPayload)
        emit("reassembled", reassembled)

        #expect(hex(identityInput) == "02001122334455667701020304050607084142")
        #expect(hex(digest) == "25429fbd15e2051049307f8e650ae863fc909a182e634a6b6c171b1aa51b4fda")
        #expect(hex(packetID) == "25429fbd15e2051049307f8e650ae863")
        let signingCore = try #require(Data(hexString: "0202000102030405060708000000000200112233445566774142"))
        #expect(signingTranscript.count == 256)
        #expect(signingTranscript.prefix(signingCore.count) == signingCore)
        #expect(signingTranscript.dropFirst(signingCore.count) == Data(repeating: 0xe6, count: 230))
        #expect(signedBytes[2] == 7)
        #expect(decodedRelay.signature == signature)
        #expect(relayedBytes[2] == 6)
        #expect(relayedTranscript == signingTranscript)
        #expect(hex(firstPayload) == "0001020304050607000000020202020301020304050607080000")
        #expect(hex(secondPayload) == "0001020304050607000100020200000200112233445566774142")
        #expect(reassembled == inner)
    }

    private func fragmentPayload(fragmentID: Data, index: UInt16, total: UInt16, data: Data) -> Data {
        var result = fragmentID
        result.append(contentsOf: withUnsafeBytes(of: index.bigEndian) { Data($0) })
        result.append(contentsOf: withUnsafeBytes(of: total.bigEndian) { Data($0) })
        result.append(MessageType.message.rawValue)
        result.append(data)
        return result
    }

    private func fragmentPacket(payload: Data, sender: Data, timestamp: UInt64) -> BitchatPacket {
        BitchatPacket(
            type: MessageType.fragment.rawValue,
            senderID: sender,
            recipientID: nil,
            timestamp: timestamp,
            payload: payload,
            signature: nil,
            ttl: 3,
            version: 2
        )
    }

    private func emit(_ id: String, _ bytes: Data) {
        print("BITMESSAGE_PHASE4 \(id) \(hex(bytes))")
    }

    private func hex(_ bytes: Data) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}
