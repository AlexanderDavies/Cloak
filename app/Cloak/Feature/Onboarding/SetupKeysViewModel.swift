import Foundation

/// Drives first-run device key registration for the onboarding screen (guide §3 / §7).
///
/// `run()` invokes the idempotent ``DeviceRegistrationService`` and publishes the resulting phase so
/// the view can show progress, surface a retry on failure, or advance once the device is registered.
@MainActor @Observable
final class SetupKeysViewModel {
    enum Phase: Equatable { case working, ready, failed }

    private(set) var phase: Phase = .working
    private let registration: DeviceRegistrationService
    private let accessToken: String

    init(registration: DeviceRegistrationService, accessToken: String) {
        self.registration = registration
        self.accessToken = accessToken
    }

    func run() async {
        phase = .working
        do {
            try await registration.ensureRegistered(accessToken: accessToken)
            phase = .ready
        } catch {
            phase = .failed
        }
    }
}
