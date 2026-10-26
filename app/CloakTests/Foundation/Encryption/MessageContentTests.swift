import Foundation
import Testing
@testable import Cloak

struct MessageContentTests {
    @Test func roundTripsThroughSerialization() throws {
        let original = MessageContent(sentAt: Date(timeIntervalSince1970: 1_700_000.123), text: "hi 🔒")
        let restored = try MessageContent.deserialize(try original.serialized())
        #expect(restored.text == "hi 🔒")
        #expect(abs(restored.sentAt.timeIntervalSince1970 - original.sentAt.timeIntervalSince1970) < 0.001)
    }
}
