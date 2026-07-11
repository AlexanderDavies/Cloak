import Testing
import Foundation
@testable import Cloak

@Suite struct UserLookupClientTests {

    // MARK: - Fixture decode

    @Test func decodesContractFixture() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice2-user-lookup", withExtension: "json"))
        let recipient = try JSONDecoder().decode(ResolvedRecipient.self, from: Data(contentsOf: url))
        #expect(recipient.sub == "a3b4c5d6-e7f8-1234-5678-9abcdef01234")
        #expect(recipient.deviceId == 1)
    }

    // MARK: - HTTP client mock tests

    @Test func lookupIssuesCorrectRequestAndUnwrapsData() async throws {
        let wrappedJSON = """
        {
          "data": {
            "sub": "a3b4c5d6-e7f8-1234-5678-9abcdef01234",
            "deviceId": 1
          }
        }
        """
        let cannedData = Data(wrappedJSON.utf8)
        let mockRunner = MockGetRunner(result: .success(cannedData))
        let client = HTTPUserLookupClient(
            baseURL: URL(string: "https://api.example.com")!,
            runner: mockRunner)

        let recipient = try await client.lookup(handle: "alice", accessToken: "my-token")

        let lastRequest = try #require(mockRunner.lastRequest)
        #expect(lastRequest.httpMethod == "GET")
        #expect(lastRequest.url?.path == "/v1/users/lookup")
        let reqURL = try #require(lastRequest.url)
        let components = try #require(URLComponents(url: reqURL, resolvingAgainstBaseURL: false))
        let handleParam = try #require(components.queryItems?.first(where: { $0.name == "handle" }))
        #expect(handleParam.value == "alice")
        #expect(lastRequest.value(forHTTPHeaderField: "Authorization") == "Bearer my-token")
        #expect(recipient.sub == "a3b4c5d6-e7f8-1234-5678-9abcdef01234")
        #expect(recipient.deviceId == 1)
    }

    @Test func lookupURLEncodesHandleWithAtSign() async throws {
        let wrappedJSON = #"{"data":{"sub":"uuid-bob","deviceId":1}}"#
        let cannedData = Data(wrappedJSON.utf8)
        let mockRunner = MockGetRunner(result: .success(cannedData))
        let client = HTTPUserLookupClient(
            baseURL: URL(string: "https://api.example.com")!,
            runner: mockRunner)

        _ = try await client.lookup(handle: "bob@example.com", accessToken: "tok")

        let lastRequest = try #require(mockRunner.lastRequest)
        let urlString = try #require(lastRequest.url?.absoluteString)
        // '@' must be percent-encoded so the raw handle never appears verbatim in the URL
        #expect(urlString.contains("%40"))
        #expect(!urlString.contains("bob@example.com"))
    }

    @Test func lookupMaps404ToNotFound() async throws {
        let mockRunner = MockGetRunner(result: .failure(HTTPStatusError.status(404)))
        let client = HTTPUserLookupClient(
            baseURL: URL(string: "https://api.example.com")!,
            runner: mockRunner)

        await #expect(throws: UserLookupError.notFound) {
            try await client.lookup(handle: "unknown", accessToken: "tok")
        }
    }
}

// MARK: - Test helpers

private final class MockGetRunner: HTTPGetRunner, @unchecked Sendable {
    var lastRequest: URLRequest?
    var result: Result<Data, Error>

    init(result: Result<Data, Error>) {
        self.result = result
    }

    func get(_ request: URLRequest) async throws -> Data {
        lastRequest = request
        return try result.get()
    }
}

/// Locates the test bundle (Swift Testing has no XCTestCase `Self.self` anchor).
private final class BundleToken {}
