import Foundation

/// Tracks whether this device has published its bundle (persisted in the encrypted DB).
protocol RegistrationState: Sendable {
    func isRegistered() -> Bool
    func markRegistered()
}

/// Orchestrates first-run device key registration (guide §7). Idempotent + resumable.
///
/// On the first launch `ensureRegistered(accessToken:)` generates key material, builds the public
/// bundle, publishes it to the server, then marks the device registered. On failure the device
/// stays unregistered so the next launch retries. On subsequent launches the method is a no-op.
struct DeviceRegistrationService: Sendable {
    let publisher: DeviceKeyPublisher
    let vault: DeviceKeyVault
    let state: RegistrationState
    let oneTimeCount: Int

    /// Generates key material, persists the private keys, then publishes the bundle once. No-op if
    /// already registered. Throws on persist or publish failure (leaving the device unregistered so
    /// the next launch retries).
    func ensureRegistered(accessToken: String) async throws {
        if state.isRegistered() { return }
        let keys = try SignalKeyGenerator.generate(oneTimeCount: oneTimeCount)
        // Persist the private keys BEFORE publishing so the matching privates back the public
        // bundle the server holds. Resume-without-regenerating (reusing persisted keys on retry
        // instead of regenerating) is a deferred refinement: regenerating on retry stays consistent
        // because persist precedes publish and the server upserts the bundle.
        try vault.persist(keys)
        let bundle = try PublicKeyBundle(from: keys, deviceId: 1)
        try await publisher.publish(bundle, accessToken: accessToken)
        state.markRegistered()
    }
}
