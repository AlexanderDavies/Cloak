import Foundation

/// Server-side pre-key bundle returned by `GET /v1/keys/{sub}`.
/// Used by the SessionEstablisher (C5) to run PQXDH.
struct RemotePreKeyBundle: Codable, Equatable, Sendable {
    struct SignedPreKey: Codable, Equatable, Sendable {
        let keyId: UInt32
        let publicKey: String
        let signature: String
    }

    struct OneTimePreKey: Codable, Equatable, Sendable {
        let keyId: UInt32
        let publicKey: String
    }

    struct KyberPreKey: Codable, Equatable, Sendable {
        let keyId: UInt32
        let publicKey: String
        let signature: String
    }

    let registrationId: UInt32
    let deviceId: UInt32
    let identityKey: String
    let signedPreKey: SignedPreKey
    /// `nil` when the server's one-time-prekey pool is exhausted.
    let oneTimePreKey: OneTimePreKey?
    /// Always present — last-resort Kyber key.
    let kyberPreKey: KyberPreKey
}

/// Fetch boundary (guide §8). Mockable; the real HTTP runner is the platform edge.
protocol PreKeyBundleClient: Sendable {
    func fetchBundle(sub: String, accessToken: String) async throws -> RemotePreKeyBundle
}

/// Minimal GET-returning-body seam so the client is unit-testable without a server.
protocol HTTPGetRunner: Sendable {
    func get(_ request: URLRequest) async throws -> Data
}

/// Fetches the remote pre-key bundle for a given subject (user UUID).
/// Calls `GET /v1/keys/{sub}` with a bearer token and unwraps the server's
/// `{ "data": { … } }` envelope.
struct HTTPPreKeyBundleClient: PreKeyBundleClient {
    let baseURL: URL
    let runner: HTTPGetRunner

    func fetchBundle(sub: String, accessToken: String) async throws -> RemotePreKeyBundle {
        let url = baseURL.appendingPathComponent("v1/keys/\(sub)")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let data = try await runner.get(request)
        return try JSONDecoder().decode(WrappedResponse<RemotePreKeyBundle>.self, from: data).data
    }
}

// MARK: - Private

/// Server response envelope. Only `data` is decoded; `errors` and `traceId` are ignored.
private struct WrappedResponse<T: Decodable>: Decodable {
    let data: T
}
