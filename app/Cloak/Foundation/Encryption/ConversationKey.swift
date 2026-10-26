import CryptoKit
import Foundation

/// Deterministic canonical conversation id shared with the server (`ConversationId.canonical`):
/// lowercase SHA-256 hex of the sorted, ':'-joined member subs. For 1:1, `subs = [mySub, peerSub]`.
enum ConversationKey {
    static func of(_ subs: [String]) -> String {
        let joined = subs.sorted().joined(separator: ":")
        let digest = SHA256.hash(data: Data(joined.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
