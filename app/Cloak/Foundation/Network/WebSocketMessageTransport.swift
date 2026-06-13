import Foundation

/// Real `MessageTransport` over `URLSessionWebSocketTask`, isolated as an actor (guide §4.2 / §8.2).
/// Reconnection, backoff, and the persisted offline outbox (guide §8.3 / §8.5) are later slices.
actor WebSocketMessageTransport: MessageTransport {
    private let url: URL
    private var task: URLSessionWebSocketTask?
    private var continuation: AsyncThrowingStream<MessageEnvelope, Error>.Continuation?

    init(url: URL) { self.url = url }

    func connect(accessToken: String) async throws {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let task = URLSession(configuration: .default).webSocketTask(with: request)
        self.task = task
        task.resume()
        receiveLoop()
    }

    func send(_ envelope: MessageEnvelope) async throws {
        try await task?.send(.string(envelope.jsonText()))
    }

    func inbound() async -> AsyncThrowingStream<MessageEnvelope, Error> {
        AsyncThrowingStream { continuation in self.continuation = continuation }
    }

    func disconnect() {
        task?.cancel(with: .goingAway, reason: nil)
        continuation?.finish()
    }

    private func receiveLoop() {
        task?.receive { [weak self] result in
            guard let self else { return }
            Task { await self.handle(result) }
        }
    }

    private func handle(_ result: Result<URLSessionWebSocketTask.Message, Error>) {
        switch result {
        case let .success(message):
            if case let .string(text) = message, let env = MessageEnvelope.decode(text: text) {
                continuation?.yield(env)
            }
            receiveLoop()                                 // keep listening for the next frame
        case let .failure(error):
            // Surface the failure (dropped connection, rejected upgrade / 401, expired token) to the
            // consumer instead of silently going deaf. Reconnection/backoff is a later slice (§8.3).
            continuation?.finish(throwing: error)
        }
    }
}
