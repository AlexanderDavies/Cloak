import Foundation

struct ChatMessage: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let mine: Bool
}

@MainActor @Observable
final class ConversationViewModel {
    private(set) var timeline: [ChatMessage] = []
    private(set) var error: String?
    private let transport: MessageTransport
    private let accessToken: String
    private var listenTask: Task<Void, Never>?

    init(transport: MessageTransport, accessToken: String) {
        self.transport = transport
        self.accessToken = accessToken
    }

    /// Pure transform of an inbound envelope to a display message, or `nil` if the blob can't be
    /// decoded. Extracted so the decode logic is unit-tested directly (no async timing).
    static func incomingMessage(from envelope: MessageEnvelope) -> ChatMessage? {
        guard let data = Data(base64Encoded: envelope.ciphertext),
              let text = String(bytes: data, encoding: .utf8) else { return nil }
        return ChatMessage(text: text, mine: false)
    }

    func start() async {
        let stream = await transport.inbound()          // subscribe before connect (transport note)
        do {
            try await transport.connect(accessToken: accessToken)
        } catch {
            self.error = "Couldn't connect. Check your connection and sign in again."
            return                                       // don't listen on a transport that never opened
        }
        listenTask = Task { [weak self] in
            do {
                for try await env in stream {
                    guard let self else { return }
                    if let message = Self.incomingMessage(from: env) {
                        self.timeline.append(message)
                    }
                }
            } catch {
                self?.error = "Connection lost. Reopen the conversation to reconnect."
            }
        }
    }

    func send(text: String, to recipientSub: String) async {
        // Skeleton: the "ciphertext" is just the bytes, base64-encoded. Real Signal
        // encryption replaces this in a later slice (guide §7).
        let blob = Data(text.utf8).base64EncodedString()
        let env = MessageEnvelope(
            messageId: UUID().uuidString, toSub: recipientSub, deviceId: nil, ciphertext: blob)
        do {
            try await transport.send(env)
            timeline.append(.init(text: text, mine: true))   // echo only after the send succeeds
        } catch {
            self.error = "Couldn't send your message. Tap to dismiss and try again."
        }
    }

    func dismissError() { error = nil }

    /// Cancel the inbound stream + close the transport. Called on view disappear (guide §3.5);
    /// a @MainActor class can't touch isolated state from a nonisolated `deinit` under Swift 6.
    func stop() async {
        listenTask?.cancel()
        listenTask = nil
        await transport.disconnect()
    }
}
