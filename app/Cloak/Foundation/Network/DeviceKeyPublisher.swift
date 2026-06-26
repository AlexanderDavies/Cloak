import Foundation

/// The bundle-publish boundary (guide §8). Mockable; the real HTTP runner is the platform edge.
protocol DeviceKeyPublisher: Sendable {
    func publish(_ bundle: PublicKeyBundle, accessToken: String) async throws
}

/// Minimal HTTP send seam so the publisher is unit-testable without a server.
protocol HTTPRunner: Sendable {
    func send(_ request: URLRequest) async throws
}

/// Encodes the public key bundle as JSON and PUTs it to `PUT /v1/keys` with a bearer token.
/// The `HTTPRunner` seam is injected so the URLSession edge stays isolated and excluded from coverage.
struct HTTPDeviceKeyPublisher: DeviceKeyPublisher {
    let baseURL: URL
    let runner: HTTPRunner

    func publish(_ bundle: PublicKeyBundle, accessToken: String) async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent("v1/keys"))
        request.httpMethod = "PUT"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(bundle)
        try await runner.send(request)
    }
}
