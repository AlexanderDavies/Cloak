import Testing
import Foundation
@testable import Cloak

@Suite struct MessageEnvelopeTests {
    @Test func decodesContractFixture() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice2-message-envelope", withExtension: "json"))
        let env = try JSONDecoder().decode(MessageEnvelope.self, from: Data(contentsOf: url))
        #expect(env.toDeviceId == 1)
        #expect(env.fromDeviceId == 1)
        #expect(env.messageType == .preKey)
        #expect(env.toSub == "a3b4c5d6-e7f8-1234-5678-9abcdef01234")
        // Delivery frame carries the server-stamped sender identity.
        #expect(env.fromSub == "f00dbabe-1111-2222-3333-444455556666")
    }

    @Test func outboundEnvelopeOmitsFromSubWhenNil() throws {
        // The inbound frame the client sends has no fromSub — it must not appear in the wire JSON,
        // so the server's inbound DTO (which has no such field) is unaffected.
        let env = MessageEnvelope(
            messageId: "m1", toSub: "bob", toDeviceId: 1, fromDeviceId: 1,
            messageType: .preKey, ciphertext: "AQID")
        #expect(env.fromSub == nil)
        #expect(!(try env.jsonText()).contains("fromSub"))
    }

    @Test func jsonTextRoundTrips() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice2-message-envelope", withExtension: "json"))
        let env = try JSONDecoder().decode(MessageEnvelope.self, from: Data(contentsOf: url))
        let decoded = try #require(MessageEnvelope.decode(text: try env.jsonText()))
        #expect(decoded == env)
    }
}

/// Locates the test bundle (Swift Testing has no XCTestCase `Self.self` anchor).
private final class BundleToken {}
