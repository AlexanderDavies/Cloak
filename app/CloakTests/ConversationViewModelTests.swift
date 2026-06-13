import Testing
import Foundation
@testable import Cloak

@Suite @MainActor struct ConversationViewModelTests {

    struct MockError: Error {}

    final class MockTransport: MessageTransport, @unchecked Sendable {
        var sent: [MessageEnvelope] = []
        var disconnected = false
        var connectError: Error?
        var sendError: Error?
        private var continuation: AsyncThrowingStream<MessageEnvelope, Error>.Continuation?
        func connect(accessToken: String) async throws { if let connectError { throw connectError } }
        func send(_ envelope: MessageEnvelope) async throws {
            if let sendError { throw sendError }
            sent.append(envelope)
        }
        func inbound() async -> AsyncThrowingStream<MessageEnvelope, Error> {
            AsyncThrowingStream { self.continuation = $0 }   // single-subscriber (matches the contract)
        }
        func disconnect() async { disconnected = true }
        func deliver(_ env: MessageEnvelope) { continuation?.yield(env) }
        func failStream(_ error: Error) { continuation?.finish(throwing: error) }
    }

    /// Awaits a condition by cooperatively yielding (no `Task.sleep` — guide §14.7). The listen task
    /// runs on the same @MainActor, so yielding lets it drain the stream; bounded to avoid a hang.
    private func waitUntil(_ condition: @MainActor () -> Bool) async {
        for _ in 0..<10_000 where !condition() { await Task.yield() }
    }

    // MARK: - Pure inbound decode (deterministic, no async)

    @Test func incomingMessage_decodesBase64Blob() {
        let blob = Data([72, 105]).base64EncodedString()   // "Hi"
        let env = MessageEnvelope(messageId: "m9", toSub: "alice-sub", deviceId: nil, ciphertext: blob)
        #expect(ConversationViewModel.incomingMessage(from: env)?.text == "Hi")
        #expect(ConversationViewModel.incomingMessage(from: env)?.mine == false)
    }

    @Test func incomingMessage_returnsNilForUndecodableBlob() {
        let env = MessageEnvelope(messageId: "m0", toSub: "alice-sub", deviceId: nil, ciphertext: "%%not-base64%%")
        #expect(ConversationViewModel.incomingMessage(from: env) == nil)
    }

    // MARK: - Behaviour against a mock transport

    @Test func sending_encodesBlob_andAppendsToTimeline() async throws {
        let transport = MockTransport()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        await model.send(text: "hello", to: "bob-sub")
        #expect(transport.sent.count == 1)
        #expect(transport.sent.first?.toSub == "bob-sub")
        #expect(model.timeline.last?.text == "hello")     // optimistic local echo
    }

    @Test func incoming_isDeliveredToTimeline() async throws {
        let transport = MockTransport()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        let blob = Data([72, 105]).base64EncodedString()   // "Hi"
        transport.deliver(MessageEnvelope(messageId: "m9", toSub: "alice-sub", deviceId: nil, ciphertext: blob))
        await waitUntil { !model.timeline.isEmpty }
        #expect(model.timeline.last?.text == "Hi")
    }

    @Test func stop_disconnectsTransport() async throws {
        let transport = MockTransport()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        await model.stop()
        #expect(transport.disconnected)
    }

    // MARK: - Error paths (surfaced, never silently dropped)

    @Test func start_surfacesConnectFailure() async {
        let transport = MockTransport()
        transport.connectError = MockError()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        #expect(model.error != nil)
    }

    @Test func send_surfacesFailure_andDoesNotEchoOrReportSent() async {
        let transport = MockTransport()
        transport.sendError = MockError()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        await model.send(text: "hello", to: "bob-sub")
        #expect(transport.sent.isEmpty)
        #expect(model.timeline.isEmpty)               // no optimistic echo when the send failed
        #expect(model.error != nil)
    }

    @Test func streamFailure_surfacesError() async {
        let transport = MockTransport()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        transport.failStream(MockError())
        await waitUntil { model.error != nil }
        #expect(model.error != nil)
    }

    @Test func dismissError_clearsIt() async {
        let transport = MockTransport()
        transport.connectError = MockError()
        let model = ConversationViewModel(transport: transport, accessToken: "t")
        await model.start()
        #expect(model.error != nil)
        model.dismissError()
        #expect(model.error == nil)
    }
}
