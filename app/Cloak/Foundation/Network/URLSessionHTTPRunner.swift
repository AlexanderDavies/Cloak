import Foundation

/// Thrown by `URLSessionHTTPRunner` and `URLSessionHTTPGetRunner` when the server returns a
/// non-2xx status code. Callers can pattern-match on the code to map specific statuses (e.g. 404)
/// to domain errors.
enum HTTPStatusError: Error {
    case status(Int)
}

/// Real `HTTPRunner` over `URLSession` (platform edge; excluded from coverage).
/// Throws `HTTPStatusError.status` on any non-2xx HTTP response.
struct URLSessionHTTPRunner: HTTPRunner {
    func send(_ request: URLRequest) async throws {
        let (_, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw HTTPStatusError.status(http.statusCode)
        }
    }
}

/// Real `HTTPGetRunner` over `URLSession` (platform edge; excluded from coverage).
/// Returns the response body as `Data`; throws `HTTPStatusError.status` on any non-2xx response.
struct URLSessionHTTPGetRunner: HTTPGetRunner {
    func get(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw HTTPStatusError.status(http.statusCode)
        }
        return data
    }
}
