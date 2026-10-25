import Testing
import Foundation
@testable import Cloak

@Suite struct MessageHistoryClientTests {

    // MARK: - Fixture decode

    @Test func decodesBacklogPageFromFixture() async throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice3-message-history", withExtension: "json"))
        let data = try Data(contentsOf: url)
        let client = HTTPMessageHistoryClient(
            baseURL: try #require(URL(string: "http://localhost")),
            runner: StubRunner(response: data))

        let page = try await client.fetchHistory(cursor: nil, limit: 50, accessToken: "t")

        #expect(page.hasMore == false)
        #expect(page.messages.count == 1)
        let message = try #require(page.messages.first)
        #expect(message.messageId == "11111111-1111-1111-1111-111111111111")
        #expect(message.fromSub == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        #expect(message.fromDeviceId == 1)
        #expect(message.toDeviceId == 1)
        #expect(message.messageType == .preKey)
        #expect(message.conversationId == "32eaa687f333efc245cc57c857f22ca5fb0e4757ea0a3c147ee2b6da131f77d0")
        #expect(message.ciphertext == "BASE64_OPAQUE")
        #expect(message.cursor.isEmpty == false)
    }

    // MARK: - HTTP request shape

    @Test func fetchHistorySendsBearerTokenAndLimit() async throws {
        let emptyPage = Data("""
        { "data": { "messages": [], "hasMore": false }, "errors": null, "traceId": null }
        """.utf8)
        let stub = StubRunner(response: emptyPage)
        let client = HTTPMessageHistoryClient(
            baseURL: try #require(URL(string: "https://api")),
            runner: stub)

        _ = try await client.fetchHistory(cursor: nil, limit: 50, accessToken: "my-token")

        let request = try #require(stub.lastRequest)
        #expect(request.httpMethod == "GET")
        #expect(request.url?.path == "/v1/messages")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer my-token")
        let requestURL = try #require(request.url)
        let components = try #require(URLComponents(url: requestURL, resolvingAgainstBaseURL: false))
        let queryItems = try #require(components.queryItems)
        #expect(queryItems.contains(URLQueryItem(name: "limit", value: "50")))
        #expect(queryItems.contains { $0.name == "cursor" } == false)
    }

    /// A dropped cursor would make the client silently re-pull page 1 forever, so this asserts the
    /// cursor is actually present (and correctly percent-encoded) on the request URL, not just passed
    /// as a parameter and forgotten.
    @Test func fetchHistoryPlacesCursorInRequestQuery() async throws {
        let emptyPage = Data("""
        { "data": { "messages": [], "hasMore": false }, "errors": null, "traceId": null }
        """.utf8)
        let stub = StubRunner(response: emptyPage)
        let client = HTTPMessageHistoryClient(
            baseURL: try #require(URL(string: "https://api")),
            runner: stub)
        let hostileCursor = "abc+def/ghi==&x=y"

        _ = try await client.fetchHistory(cursor: hostileCursor, limit: 10, accessToken: "t")

        let request = try #require(stub.lastRequest)
        let requestURL = try #require(request.url)
        let components = try #require(URLComponents(url: requestURL, resolvingAgainstBaseURL: false))
        let queryItems = try #require(components.queryItems)
        #expect(queryItems.contains(URLQueryItem(name: "cursor", value: hostileCursor)))
        #expect(queryItems.contains(URLQueryItem(name: "limit", value: "10")))
    }
}

// MARK: - Test helpers

private final class StubRunner: HTTPGetRunner, @unchecked Sendable {
    let response: Data
    private(set) var lastRequest: URLRequest?

    init(response: Data) {
        self.response = response
    }

    func get(_ request: URLRequest) async throws -> Data {
        lastRequest = request
        return response
    }
}

/// Locates the test bundle (Swift Testing has no XCTestCase `Self.self` anchor).
private final class BundleToken {}
