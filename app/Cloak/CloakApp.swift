import SwiftUI

@main
struct CloakApp: App {
    // Composition root (guide §2.5 / §5.3). Local-dev defaults; override per environment later.
    private let issuer = URL(string: "http://localhost:8081/realms/cloak")
    private let transportURL = URL(string: "ws://localhost:8080/ws")

    var body: some Scene {
        WindowGroup {
            if let issuer, let transportURL {
                RootView(
                    auth: KeycloakAuthService(issuer: issuer),
                    transportURL: transportURL)
            } else {
                Text("Misconfigured")
            }
        }
    }
}
