import SwiftUI
import UIKit

/// Skeleton root: sign in via Keycloak (OIDC-PKCE), then run first-run key setup, then open the
/// conversation list. Pure UI wiring — verified by manual E2E (`./dev.sh up` + run) and the injected
/// view models' units, not by units on the view itself (guide §14.5).
struct RootView: View {
    private let auth: AuthService
    private let registration: DeviceRegistrationService

    @State private var setupModel: SetupKeysViewModel?
    @State private var ready = false
    @State private var error: String?
    @State private var signingIn = false

    init(auth: AuthService, registration: DeviceRegistrationService) {
        self.auth = auth
        self.registration = registration
    }

    var body: some View {
        Group {
            if ready {
                NavigationStack { ConversationListView() }
            } else if let setupModel {
                SetupKeysView(model: setupModel) { ready = true }
            } else {
                signIn
            }
        }
    }

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
