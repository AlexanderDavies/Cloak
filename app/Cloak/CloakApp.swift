import SwiftUI

@main
struct CloakApp: App {
    // Composition root (guide §2.5 / §5.3). Local-dev defaults; override per environment later.
    private let issuer = URL(string: "http://localhost:8081/realms/cloak")
    private let baseURL = URL(string: "http://localhost:8080")

    var body: some Scene {
        WindowGroup {
            rootView
        }
    }

    @ViewBuilder
    private var rootView: some View {
        if let issuer, let baseURL, let registration = Self.makeRegistration(baseURL: baseURL) {
            RootView(auth: KeycloakAuthService(issuer: issuer), registration: registration)
        } else {
            // `EncryptedDatabase.openDefault()` can throw (Keychain/SQLCipher) and the URLs can fail
            // to parse; either way surface a static error rather than crashing the app at launch.
            Text("Misconfigured")
        }
    }

    /// Builds the real device-registration service graph, returning `nil` if the encrypted store
    /// can't be opened so the caller can fall back to the error state.
    private static func makeRegistration(baseURL: URL) -> DeviceRegistrationService? {
        do {
            let database = try EncryptedDatabase.openDefault()
            return DeviceRegistrationService(
                publisher: HTTPDeviceKeyPublisher(baseURL: baseURL, runner: URLSessionHTTPRunner()),
                vault: GRDBDeviceKeyVault(database: database),
                state: try GRDBRegistrationState(database: database),
                oneTimeCount: 100)
        } catch {
            return nil
        }
    }
}
