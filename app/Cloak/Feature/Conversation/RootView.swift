import GRDB
import LibSignalClient
import SwiftUI
import UIKit

/// Skeleton root: sign in via Keycloak (OIDC-PKCE), then run first-run key setup, then open the
/// conversation list. Pure UI wiring — verified by manual E2E (`./dev.sh up` + run) and the injected
/// view models' units, not by units on the view itself (guide §14.5).
struct RootView: View {
    private let auth: AuthService
    private let registration: DeviceRegistrationService
    private let database: DatabaseQueue
    private let baseURL: URL

    @State private var setupModel: SetupKeysViewModel?
    /// Non-nil once the Signal key store has been reloaded from disk after first-run registration.
    @State private var messagingStore: SignalKeyStore?
    /// The JWT access token captured at login; used to derive `mySub` and as the token-provider value.
    @State private var capturedToken: String?
    @State private var error: String?
    @State private var signingIn = false

    init(
        auth: AuthService,
        registration: DeviceRegistrationService,
        database: DatabaseQueue,
        baseURL: URL
    ) {
        self.auth = auth
        self.registration = registration
        self.database = database
        self.baseURL = baseURL
    }

    var body: some View {
        Group {
            if let store = messagingStore,
               let token = capturedToken,
               let mySub = JWT.subject(of: token),
               let localAddress = try? ProtocolAddress(name: mySub, deviceId: 1),
               let wsURL = Self.websocketURL(from: baseURL) {
                // Build the Slice-2 messaging graph and hand it to the conversation list.
                // Structs (lookup, bundleClient, establisher, crypto) are value types — recreating them
                // on each body evaluation is fine; ConversationListView preserves its @State (path).
                let lookup = HTTPUserLookupClient(baseURL: baseURL, runner: URLSessionHTTPGetRunner())
                let bundleClient = HTTPPreKeyBundleClient(baseURL: baseURL, runner: URLSessionHTTPGetRunner())
                let establisher = SessionEstablisher(store: store, localAddress: localAddress)
                let crypto = MessageCrypto(store: store, localAddress: localAddress)
                let tok = token  // name alias so the @Sendable capture is unambiguous
                let tokenProvider: @Sendable () async throws -> String = { tok }
                ConversationListView(
                    lookup: lookup,
                    bundleClient: bundleClient,
                    establisher: establisher,
                    crypto: crypto,
                    wsURL: wsURL,
                    tokenProvider: tokenProvider)
            } else if let setupModel {
                SetupKeysView(model: setupModel) { onSetupComplete() }
            } else {
                signIn
            }
        }
    }

    // MARK: - Setup completion

    /// Called by `SetupKeysView` once first-run registration is done. Reloads the persisted
    /// `SignalKeyStore` from disk so the messaging graph can be assembled.
    private func onSetupComplete() {
        do {
            messagingStore = try loadSignalKeyStore()
            if messagingStore == nil {
                error = "Encryption keys missing after setup — please restart the app"
            }
        } catch {
            self.error = "Failed to load encryption keys: \(error.localizedDescription)"
        }
    }

    /// Rebuilds a `SignalKeyStore` from the identity that `GRDBDeviceKeyVault.persist` wrote to the
    /// `local_identity` table during registration. Returns `nil` if no identity row exists yet.
    private func loadSignalKeyStore() throws -> SignalKeyStore? {
        guard let (identity, regId) = try SignalKeyStore.loadLocalIdentity(database) else { return nil }
        return try SignalKeyStore(database: database, identity: identity, registrationId: regId)
    }

    /// Converts an HTTP(S) base URL to its WebSocket (WS/WSS) counterpart at path `/ws`.
    private static func websocketURL(from baseURL: URL) -> URL? {
        var comps = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        comps?.scheme = (baseURL.scheme == "https") ? "wss" : "ws"
        comps?.path = "/ws"
        return comps?.url
    }

    // MARK: - Sign-in view

    private var signIn: some View {
        VStack(spacing: 16) {
            Image("CloakLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 96, height: 96)
                .clipShape(RoundedRectangle(cornerRadius: 22))
            Text("Cloak").font(.largeTitle.bold())
            if let error { Text(error).foregroundStyle(.red).font(.footnote) }
            Button(signingIn ? "Signing in…" : "Sign in") { Task { await signInTapped() } }
                .disabled(signingIn)
        }
    }

    @MainActor
    private func signInTapped() async {
        guard let presenter = Self.topViewController() else {
            error = "No window to present sign-in"
            return
        }
        signingIn = true
        defer { signingIn = false }
        do {
            let token = try await auth.login(presenting: presenter)
            capturedToken = token
            error = nil
            setupModel = SetupKeysViewModel(registration: registration, accessToken: token)
        } catch {
            self.error = "Sign-in failed"
        }
    }

    @MainActor
    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.first { $0.activationState == .foregroundActive }
        return (scene as? UIWindowScene)?.keyWindow?.rootViewController
    }
}
