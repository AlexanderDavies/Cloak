import Testing
import Foundation
@testable import Cloak

@Suite @MainActor struct StartConversationViewModelTests {

    // MARK: - Terminal-state tests (FixedLookupMock returns immediately)

    @Test func resolvableHandleReachesReady() async {
        let recipient = ResolvedRecipient(sub: "user-abc", deviceId: 1)
        let mock = FixedLookupMock(result: .success(recipient))
        let model = StartConversationViewModel(lookup: mock, tokenProvider: { "test-token" })
        model.handle = "alice@example.com"

        await model.resolve()

        #expect(model.state == .ready(recipient))
    }

    @Test func notFoundHandleReachesNotFound() async {
        let mock = FixedLookupMock(result: .failure(UserLookupError.notFound))
        let model = StartConversationViewModel(lookup: mock, tokenProvider: { "test-token" })
        model.handle = "unknown@example.com"

        await model.resolve()

        #expect(model.state == .notFound)
    }

    @Test func genericErrorReachesFailed() async {
        struct SomeError: Error {}
        let mock = FixedLookupMock(result: .failure(SomeError()))
        let model = StartConversationViewModel(lookup: mock, tokenProvider: { "test-token" })
        model.handle = "alice@example.com"

        await model.resolve()

        guard case .failed = model.state else {
            Issue.record("Expected .failed, got \(model.state)")
            return
        }
    }

    // MARK: - In-flight .lookingUp transition

    /// Verifies that `state == .lookingUp` is set before the lookup completes.
    ///
    /// Strategy: `PausingLookupMock` suspends indefinitely until `resume()` is called.
    /// Two yields give the model task time to set `.lookingUp`, get the token, and suspend
    /// at `lookup.lookup()`. After confirming `.lookingUp`, we release the mock and let the
    /// task finish.
    @Test func setsLookingUpDuringLookup() async {
        let recipient = ResolvedRecipient(sub: "user-abc", deviceId: 1)
        let mock = PausingLookupMock(returning: recipient)
        let model = StartConversationViewModel(lookup: mock, tokenProvider: { "test-token" })
        model.handle = "alice@example.com"

        #expect(model.state == .idle)

        let task = Task { @MainActor in await model.resolve() }

        // First yield: model task starts, sets .lookingUp, calls (fast) tokenProvider.
        await Task.yield()
        // Second yield: ensures any tokenProvider continuation has been processed so the
        // model reaches lookup.lookup() and is now suspended there.
        await Task.yield()

        #expect(model.state == .lookingUp)

        mock.resume()
        await task.value

        #expect(model.state == .ready(recipient))
    }

    /// Verifies that the token is forwarded to the lookup client.
    @Test func tokenProviderResultPassedToLookup() async throws {
        let recipient = ResolvedRecipient(sub: "user-abc", deviceId: 1)
        let mock = CapturingLookupMock(result: .success(recipient))
        let model = StartConversationViewModel(lookup: mock, tokenProvider: { "my-token" })
        model.handle = "alice@example.com"

        await model.resolve()

        #expect(mock.capturedToken == "my-token")
    }
}

// MARK: - Mock helpers

/// Returns a fixed result immediately (no suspension).
private final class FixedLookupMock: UserLookupClient, @unchecked Sendable {
    private let result: Result<ResolvedRecipient, Error>

    init(result: Result<ResolvedRecipient, Error>) {
        self.result = result
    }

    func lookup(handle: String, accessToken: String) async throws -> ResolvedRecipient {
        try result.get()
    }
}

/// Suspends `lookup` indefinitely until `resume()` is called, allowing mid-flight
/// state inspection from the `@MainActor` test.
private final class PausingLookupMock: UserLookupClient, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<ResolvedRecipient, Error>?
    /// Set when `resume()` is called before `lookup` has stored its continuation, so the pending
    /// resume is honoured the instant the continuation arrives. Without this, an early `resume()`
    /// is lost and `lookup` suspends forever (deadlock).
    private var resumePending = false
    private let returnValue: ResolvedRecipient

    init(returning returnValue: ResolvedRecipient) {
        self.returnValue = returnValue
    }

    func lookup(handle: String, accessToken: String) async throws -> ResolvedRecipient {
        try await withCheckedThrowingContinuation { cont in
            let resumeNow = lock.withLock { () -> Bool in
                if resumePending {
                    resumePending = false
                    return true
                }
                continuation = cont
                return false
            }
            if resumeNow { cont.resume(returning: returnValue) }
        }
    }

    /// Releases the suspended `lookup` call so it returns the configured value. Safe to call
    /// before `lookup` suspends: the resume is latched and applied when the continuation arrives.
    func resume() {
        let cont = lock.withLock { () -> CheckedContinuation<ResolvedRecipient, Error>? in
            if let stored = continuation {
                continuation = nil
                return stored
            }
            resumePending = true
            return nil
        }
        cont?.resume(returning: returnValue)
    }
}

/// Records the access token passed to `lookup`.
private final class CapturingLookupMock: UserLookupClient, @unchecked Sendable {
    private let result: Result<ResolvedRecipient, Error>
    private(set) var capturedToken: String?

    init(result: Result<ResolvedRecipient, Error>) {
        self.result = result
    }

    func lookup(handle: String, accessToken: String) async throws -> ResolvedRecipient {
        capturedToken = accessToken
        return try result.get()
    }
}
