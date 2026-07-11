import Testing
import Foundation
@testable import Cloak

@Suite struct PreKeyBundleClientTests {

    // MARK: - Fixture decode

    @Test func decodesContractFixtureWithOneTimePreKey() throws {
        let url = try #require(
            Bundle(for: BundleToken.self).url(forResource: "slice2-prekey-bundle", withExtension: "json"))
        let bundle = try JSONDecoder().decode(RemotePreKeyBundle.self, from: Data(contentsOf: url))
        #expect(bundle.registrationId == 12345)
        #expect(bundle.deviceId == 1)
        #expect(bundle.signedPreKey.keyId == 1)
        #expect(bundle.oneTimePreKey != nil)
        #expect(bundle.kyberPreKey.keyId == 1)
    }

    @Test func decodesFixtureWithoutOneTimePreKeyToNil() throws {
        let json = """
        {
          "registrationId": 99,
          "deviceId": 2,
          "identityKey": "BQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "signedPreKey": {
            "keyId": 7,
            "publicKey": "BQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "signature": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
          },
          "kyberPreKey": {
            "keyId": 3,
            "publicKey": "AAAA",
            "signature": "AAAA"
          }
        }
        """
        let data = Data(json.utf8)
        let bundle = try JSONDecoder().decode(RemotePreKeyBundle.self, from: data)
        #expect(bundle.oneTimePreKey == nil)
        #expect(bundle.signedPreKey.keyId == 7)
        #expect(bundle.kyberPreKey.keyId == 3)
    }

    // MARK: - HTTP client mock test

    @Test func fetchBundleRequestsCorrectURLAndUnwrapsData() async throws {
        let wrappedJSON = """
        {
          "data": {
            "registrationId": 12345,
            "deviceId": 1,
            "identityKey": "BQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "signedPreKey": {
              "keyId": 1,
              "publicKey": "BQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
              "signature": "AAAA"
            },
            "oneTimePreKey": {
              "keyId": 42,
              "publicKey": "BQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            },
            "kyberPreKey": {
              "keyId": 1,
              "publicKey": "AAAA",
              "signature": "AAAA"
            }
          },
          "errors": [],
          "traceId": "test-trace-id"
        }
        """
        let cannedData = Data(wrappedJSON.utf8)
        let mockRunner = MockGetRunner(result: .success(cannedData))
        let client = HTTPPreKeyBundleClient(
            baseURL: URL(string: "https://api")!,
            runner: mockRunner)

        let bundle = try await client.fetchBundle(sub: "alice-uuid", accessToken: "my-token")

        let lastRequest = try #require(mockRunner.lastRequest)
        #expect(lastRequest.httpMethod == "GET")
        #expect(lastRequest.url?.path == "/v1/keys/alice-uuid")
        #expect(lastRequest.value(forHTTPHeaderField: "Authorization") == "Bearer my-token")
        #expect(bundle.registrationId == 12345)
        #expect(bundle.oneTimePreKey?.keyId == 42)
        #expect(bundle.kyberPreKey.keyId == 1)
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
