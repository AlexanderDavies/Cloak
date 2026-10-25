import Testing
@testable import Cloak

@Suite struct WebSocketEncodingTests {
    @Test func encodesEnvelopeToJSONText() throws {
        let env = MessageEnvelope(
            messageId: "m1", toSub: "bob-sub",
            toDeviceId: 1, fromDeviceId: 2,
            messageType: .normal, conversationId: "convo-1", ciphertext: "AQID")
        let text = try env.jsonText()
        #expect(text.contains("\"toSub\":\"bob-sub\""))
        #expect(text.contains("\"ciphertext\":\"AQID\""))
    }

    @Test func decodeRoundTripsEncodedEnvelope() throws {
        let env = MessageEnvelope(
            messageId: "m1", toSub: "bob-sub",
            toDeviceId: 1, fromDeviceId: 2,
            messageType: .preKey, conversationId: "convo-1", ciphertext: "AQID")
        #expect(MessageEnvelope.decode(text: try env.jsonText()) == env)
    }

    @Test func decodeReturnsNilForNonEnvelopeText() {
        #expect(MessageEnvelope.decode(text: "not json") == nil)
        #expect(MessageEnvelope.decode(text: #"{"toSub":"bob"}"#) == nil)   // missing required fields
    }
}
