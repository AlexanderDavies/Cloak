import Foundation

/// A user resolved from a handle (exact email address or username).
struct ResolvedRecipient: Codable, Equatable, Hashable, Sendable {
    let sub: String
    let deviceId: UInt32
}

/// Errors returned by `UserLookupClient`.
enum UserLookupError: Error, Equatable {
    /// No user was found for the given handle.
    case notFound
}

/// Resolves a handle (exact email or username) to a `ResolvedRecipient`.
/// Mockable fetch boundary (guide §8).
protocol UserLookupClient: Sendable {
    func lookup(handle: String, accessToken: String) async throws -> ResolvedRecipient
}

/// Calls `GET /v1/users/lookup?handle=<handle>` with a bearer token and unwraps the server's
/// `{ "data": { … } }` envelope. HTTP 404 is mapped to `UserLookupError.notFound`.
struct HTTPUserLookupClient: UserLookupClient {
    let baseURL: URL
    let runner: HTTPGetRunner

    func lookup(handle: String, accessToken: String) async throws -> ResolvedRecipient {
        let base = baseURL.appendingPathComponent("v1/users/lookup")
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            preconditionFailure("baseURL must be a valid URL")
        }
        // Use percentEncodedQueryItems so that characters like '@' that are valid in RFC 3986
        // query strings but ambiguous in query components are always percent-encoded.
        var queryComponentAllowed = CharacterSet.urlQueryAllowed
        queryComponentAllowed.remove(charactersIn: "@+&=?#[]")
        let encodedHandle = handle.addingPercentEncoding(withAllowedCharacters: queryComponentAllowed) ?? handle
        components.percentEncodedQueryItems = [URLQueryItem(name: "handle", value: encodedHandle)]
        guard let resolvedURL = components.url else {
            preconditionFailure("URLComponents must produce a valid URL")
        }
        var request = URLRequest(url: resolvedURL)
        request.httpMethod = "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        do {
            let data = try await runner.get(request)
            return try JSONDecoder().decode(WrappedResponse<ResolvedRecipient>.self, from: data).data
        } catch HTTPStatusError.status(404) {
            throw UserLookupError.notFound
        }
    }
}

// MARK: - Private

private struct WrappedResponse<T: Decodable>: Decodable {
    let data: T
}
