import Testing
import Foundation
@testable import Cloak

@Suite struct DeviceKeyPublisherTests {
    final class MockRunner: HTTPRunner, @unchecked Sendable {
        var lastRequest: URLRequest?
        var result: Result<Void, Error> = .success(())
        func send(_ request: URLRequest) async throws {
            lastRequest = request
            try result.get()
        }
    }

    @Test func putsBundleWithBearer() async throws {
        let runner = MockRunner()
        let publisher = HTTPDeviceKeyPublisher(baseURL: URL(string: "https://api")!, runner: runner)
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        try await publisher.publish(PublicKeyBundle(from: keys, deviceId: 1), accessToken: "tok")
        #expect(runner.lastRequest?.httpMethod == "PUT")
        #expect(runner.lastRequest?.url?.path == "/v1/keys")
        #expect(runner.lastRequest?.value(forHTTPHeaderField: "Authorization") == "Bearer tok")
    }
}
