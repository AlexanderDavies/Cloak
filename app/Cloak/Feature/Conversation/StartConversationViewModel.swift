import Foundation

/// Drives the start-conversation screen: resolves a typed handle to a `ResolvedRecipient`.
///
/// State machine:
/// - `.idle` → user hasn't tapped "Find" yet
/// - `.lookingUp` → resolution in progress
/// - `.ready(ResolvedRecipient)` → resolved; view navigates to the chat thread
/// - `.notFound` → no user with that handle exists
/// - `.failed(String)` → unexpected error; message surfaced to the user (guide §11)
///
/// Guide §3: `@Observable` view model — all behaviour here; the view is a dumb pass-through.
/// Guide §4: `@MainActor` for Swift 6 strict concurrency; `tokenProvider` is `@Sendable`.
@MainActor @Observable
final class StartConversationViewModel {
    enum State: Equatable {
        case idle
        case lookingUp
        case ready(ResolvedRecipient)
        case notFound
        case failed(String)
    }

    private(set) var state: State = .idle
    var handle: String = ""

    private let lookup: UserLookupClient
    private let tokenProvider: @Sendable () async throws -> String

    init(lookup: UserLookupClient, tokenProvider: @escaping @Sendable () async throws -> String) {
        self.lookup = lookup
        self.tokenProvider = tokenProvider
    }

    /// Resolves `handle` to a `ResolvedRecipient`.
    ///
    /// Sets `.lookingUp` immediately, then either `.ready`, `.notFound`, or `.failed`.
    /// Surfaced-error discipline (guide §11): every error path updates `state`; none are swallowed.
    func resolve() async {
        state = .lookingUp
        do {
            let token = try await tokenProvider()
            let recipient = try await lookup.lookup(handle: handle, accessToken: token)
            state = .ready(recipient)
        } catch UserLookupError.notFound {
            state = .notFound
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}
