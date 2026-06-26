import Foundation

/// Real `HTTPRunner` over `URLSession` (platform edge; excluded from coverage).
/// Throws `HTTPError.status` on any non-2xx HTTP response.
struct URLSessionHTTPRunner: HTTPRunner {
    enum HTTPError: Error { case status(Int) }

    func send(_ request: URLRequest) async throws {
        let (_, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw HTTPError.status(http.statusCode)
        }
    }
}
