import Foundation

struct BacklogMessage: Codable, Equatable, Sendable {
    let messageId: String
    let fromSub: String
    let fromDeviceId: UInt32
    let toDeviceId: UInt32
    let messageType: SignalMessageType
    let conversationId: String
    let ciphertext: String
    let cursor: String
}

struct BacklogPage: Codable, Equatable, Sendable {
    let messages: [BacklogMessage]
    let hasMore: Bool
}

protocol MessageHistoryClient: Sendable {
    func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage
}

/// Calls `GET /v1/messages?cursor=&limit=` with a bearer token and unwraps the `{ "data": … }` envelope.
struct HTTPMessageHistoryClient: MessageHistoryClient {
    let baseURL: URL
    let runner: HTTPGetRunner

    func fetchHistory(cursor: String?, limit: Int, accessToken: String) async throws -> BacklogPage {
        let base = baseURL.appendingPathComponent("v1/messages")
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            preconditionFailure("baseURL must be a valid URL")
        }
        var items = [URLQueryItem(name: "limit", value: String(limit))]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        components.queryItems = items
        guard let url = components.url else { preconditionFailure("URLComponents must produce a URL") }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let data = try await runner.get(request)
        return try JSONDecoder().decode(HistoryEnvelope.self, from: data).data
    }

    private struct HistoryEnvelope: Decodable { let data: BacklogPage }
}
