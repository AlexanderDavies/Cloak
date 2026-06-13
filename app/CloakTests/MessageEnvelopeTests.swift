import Testing
import Foundation
@testable import Cloak

@Suite struct MessageEnvelopeTests {
    @Test func decodesContractFixture() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "phase0-message-envelope", withExtension: "json"))
        let env = try JSONDecoder().decode(MessageEnvelope.self, from: Data(contentsOf: url))
        #expect(env.toSub == "bob-sub")
        #expect(env.ciphertext == "AQID")   // base64 of [1,2,3]
        #expect(env.deviceId == nil)
    }
}

/// Locates the test bundle (Swift Testing has no XCTestCase `Self.self` anchor).
private final class BundleToken {}
