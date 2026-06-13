import SwiftUI
import UIKit

/// Skeleton root: sign in via Keycloak (OIDC-PKCE), then open the conversation over the authenticated
/// WebSocket. Pure UI wiring — verified by manual E2E (`./dev.sh up` + run), not units (guide §14.5).
struct RootView: View {
    private let auth: AuthService
    private let transportURL: URL

    @State private var model: ConversationViewModel?
    @State private var mySub = ""
    @State private var defaultRecipient = ""
    @State private var error: String?
    @State private var signingIn = false

    init(auth: AuthService, transportURL: URL) {
        self.auth = auth
        self.transportURL = transportURL
    }

    var body: some View {
        Group {
            if let model {
                ConversationView(model: model, recipientSub: defaultRecipient, mySub: mySub)
            } else {
                VStack(spacing: 16) {
                    Image("CloakLogo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 96, height: 96)
                        .clipShape(RoundedRectangle(cornerRadius: 22))
                    Text("Cloak").font(.largeTitle.bold())
                    if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                    Button(signingIn ? "Signing in…" : "Sign in") { Task { await signIn() } }
                        .disabled(signingIn)
                }
            }
        }
    }

    @MainActor
    private func signIn() async {
        guard let presenter = Self.topViewController() else {
            error = "No window to present sign-in"
            return
        }
        signingIn = true
        defer { signingIn = false }
        do {
            let token = try await auth.login(presenting: presenter)
            mySub = JWT.subject(of: token) ?? ""
            defaultRecipient = DemoUser.other(than: mySub)   // default to the *other* seeded user
            model = ConversationViewModel(
                transport: WebSocketMessageTransport(url: transportURL),
                accessToken: token)
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

/// The two seeded local-dev users (see iam/realm). Demo-only convenience so each device defaults its
/// recipient to the *other* user; real contact/recipient resolution is a later slice.
private enum DemoUser {
    static let alice = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    static let bob = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    static func other(than sub: String) -> String {
        switch sub {
        case alice: return bob
        case bob: return alice
        default: return ""
        }
    }
}
