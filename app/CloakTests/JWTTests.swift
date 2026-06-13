import Testing
import Foundation
@testable import Cloak

@Suite struct JWTTests {
    /// Builds a base64url JWT segment (no padding) from a JSON string.
    private func segment(_ json: String) -> String {
        Data(json.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    @Test func subject_decodesSubClaim() {
        let token = "header.\(segment(#"{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","x":1}"#)).sig"
        #expect(JWT.subject(of: token) == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    @Test func subject_isNilForMalformedToken() {
        #expect(JWT.subject(of: "not-a-jwt") == nil)
    }

    @Test func subject_isNilWhenClaimMissing() {
        let token = "header.\(segment(#"{"email":"a@b.c"}"#)).sig"
        #expect(JWT.subject(of: token) == nil)
    }
}
