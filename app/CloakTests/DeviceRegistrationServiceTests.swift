import Testing
import Foundation
@testable import Cloak

@Suite struct DeviceRegistrationServiceTests {
    actor MockPublisher: DeviceKeyPublisher {
        var publishCount = 0
        var shouldFail = false
        func publish(_ bundle: PublicKeyBundle, accessToken: String) async throws {
            publishCount += 1
            if shouldFail { throw NSError(domain: "x", code: 1) }
        }
        func setFail(_ value: Bool) { shouldFail = value }
    }

    /// In-memory `RegistrationState` — reused by `SetupKeysViewModelTests` (E8).
    final class MemoryState: RegistrationState, @unchecked Sendable {
        var registered = false
        func isRegistered() -> Bool { registered }
        func markRegistered() { registered = true }
    }

    /// In-memory `DeviceKeyVault` recording persist calls — reused by E8b.
    final class MockVault: DeviceKeyVault, @unchecked Sendable {
        var persistCount = 0
        var lastKeys: GeneratedDeviceKeys?
        var identityPresent = false
        func persist(_ keys: GeneratedDeviceKeys) throws {
            persistCount += 1
            lastKeys = keys
        }
        func hasIdentity() -> Bool { identityPresent }
    }

    @Test func firstRun_generatesPersistsPublishesMarks() async throws {
        let pub = MockPublisher(); let vault = MockVault(); let state = MemoryState()
        let svc = DeviceRegistrationService(
            publisher: pub, vault: vault, state: state, oneTimeCount: 5)
        try await svc.ensureRegistered(accessToken: "t")
        #expect(state.registered)
        #expect(vault.persistCount == 1)
        #expect(await pub.publishCount == 1)
    }

    @Test func relaunch_skipsWhenAlreadyRegistered() async throws {
        let pub = MockPublisher(); let vault = MockVault(); let state = MemoryState()
        state.registered = true
        let svc = DeviceRegistrationService(
            publisher: pub, vault: vault, state: state, oneTimeCount: 5)
        try await svc.ensureRegistered(accessToken: "t")
        #expect(vault.persistCount == 0)
        #expect(await pub.publishCount == 0)
    }

    @Test func failedPublish_persistsKeys_butStaysUnregistered_andThrows() async {
        let pub = MockPublisher(); await pub.setFail(true)
        let vault = MockVault(); let state = MemoryState()
        let svc = DeviceRegistrationService(
            publisher: pub, vault: vault, state: state, oneTimeCount: 5)
        await #expect(throws: (any Error).self) { try await svc.ensureRegistered(accessToken: "t") }
        #expect(vault.persistCount == 1)
        #expect(state.registered == false)
    }
}
