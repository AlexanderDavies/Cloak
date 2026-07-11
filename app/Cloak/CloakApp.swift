import GRDB
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
        if let issuer, let baseURL, let (registration, database) = Self.makeGraph(baseURL: baseURL) {
            RootView(
                auth: KeycloakAuthService(issuer: issuer),
                registration: registration,
                database: database,
                baseURL: baseURL)
        } else {
            // `EncryptedDatabase.openDefault()` can throw (Keychain/SQLCipher) and the URLs can fail
            // to parse; either way surface a static error rather than crashing the app at launch.
            Text("Misconfigured")
        }
    }

    /// Opens the encrypted store, builds the device-registration service, and returns both so the
    /// composition root can rebuild `SignalKeyStore` from the persisted identity after registration.
    /// Returns `nil` if the store cannot be opened so the caller can fall back to the error state.
    private static func makeGraph(baseURL: URL) -> (DeviceRegistrationService, DatabaseQueue)? {
        do {
            let database = try EncryptedDatabase.openDefault()
            let registration = DeviceRegistrationService(
                publisher: HTTPDeviceKeyPublisher(baseURL: baseURL, runner: URLSessionHTTPRunner()),
                vault: GRDBDeviceKeyVault(database: database),
                state: try GRDBRegistrationState(database: database),
                oneTimeCount: 100)
            return (registration, database)
        } catch {
            return nil
        }
    }
}
