import Foundation

/// Minimal, display-only JWT decoding. Reads a claim out of the (unverified) payload — the signature is
/// validated server-side, never here. Used to show the signed-in user their own `sub`.
enum JWT {
    /// The `sub` claim from a JWT access token, or `nil` if the token is malformed / has no `sub`.
    static func subject(of token: String) -> String? {
        let segments = token.split(separator: ".")
        guard segments.count >= 2 else { return nil }
        var base64 = String(segments[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }   // restore base64url padding
        guard let data = Data(base64Encoded: base64),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sub = object["sub"] as? String else { return nil }
        return sub
    }
}
