import Testing
import Foundation
@testable import Cloak

@Suite struct MessageEnvelopeTests {
    // NOTE: adapted from the slice2 fixture to the slice3 fixture — `conversationId` is now a
    // required (non-optional) field, and the slice2 fixture (frozen historical contract record,
    // predating that field) no longer decodes. Slice3 supersedes slice2 as the current wire
    // contract, so this general-shape assertion now exercises the slice3 fixture instead.
    @Test func decodesContractFixture() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice3-message-envelope", withExtension: "json"))
        let env = try JSONDecoder().decode(MessageEnvelope.self, from: Data(contentsOf: url))
        #expect(env.toDeviceId == 1)
        #expect(env.fromDeviceId == 1)
        #expect(env.messageType == .preKey)
        #expect(env.toSub == "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        // Delivery frame carries the server-stamped sender identity.
        #expect(env.fromSub == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    @Test func decodesConversationIdFromSlice3Fixture() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice3-message-envelope", withExtension: "json"))
        let env = try JSONDecoder().decode(MessageEnvelope.self, from: Data(contentsOf: url))
        #expect(env.conversationId == "32eaa687f333efc245cc57c857f22ca5fb0e4757ea0a3c147ee2b6da131f77d0")
    }

    @Test func outboundEnvelopeOmitsFromSubWhenNil() throws {
        // The inbound frame the client sends has no fromSub — it must not appear in the wire JSON,
        // so the server's inbound DTO (which has no such field) is unaffected.
        let env = MessageEnvelope(
            messageId: "m1", toSub: "bob", toDeviceId: 1, fromDeviceId: 1,
            messageType: .preKey, conversationId: "convo-1", ciphertext: "AQID")
        #expect(env.fromSub == nil)
        #expect(!(try env.jsonText()).contains("fromSub"))
    }

    // NOTE: adapted from the slice2 fixture to the slice3 fixture — see comment on
    // `decodesContractFixture` above; the round-trip property being tested is unchanged.
    @Test func jsonTextRoundTrips() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice3-message-envelope", withExtension: "json"))
        let env = try JSONDecoder().decode(MessageEnvelope.self, from: Data(contentsOf: url))
        let decoded = try #require(MessageEnvelope.decode(text: try env.jsonText()))
        #expect(decoded == env)
    }
}

/// Locates the test bundle (Swift Testing has no XCTestCase `Self.self` anchor).
private final class BundleToken {}
