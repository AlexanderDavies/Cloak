import Testing
import Foundation
@testable import Cloak

@Suite @MainActor struct SetupKeysViewModelTests {
    private typealias MemoryState = DeviceRegistrationServiceTests.MemoryState
    private typealias MockVault = DeviceRegistrationServiceTests.MockVault
    private typealias MockPublisher = DeviceRegistrationServiceTests.MockPublisher

    @Test func reachesReadyOnSuccess() async {
        let svc = DeviceRegistrationService(
            publisher: MockPublisher(), vault: MockVault(), state: MemoryState(), oneTimeCount: 3)
        let model = SetupKeysViewModel(registration: svc, accessToken: "t")
        await model.run()
        #expect(model.phase == .ready)
    }

    @Test func reachesFailedOnPublishError() async {
        let pub = MockPublisher()
        await pub.setFail(true)
        let svc = DeviceRegistrationService(
            publisher: pub, vault: MockVault(), state: MemoryState(), oneTimeCount: 3)
        let model = SetupKeysViewModel(registration: svc, accessToken: "t")
        await model.run()
        #expect(model.phase == .failed)
    }

    @Test func retryAfterFailureReachesReady() async {
        // Same state object so a successful retry marks it registered; flip the publisher to succeed.
        let pub = MockPublisher()
        await pub.setFail(true)
        let state = MemoryState()
        let svc = DeviceRegistrationService(
            publisher: pub, vault: MockVault(), state: state, oneTimeCount: 3)
        let model = SetupKeysViewModel(registration: svc, accessToken: "t")
        await model.run()
        #expect(model.phase == .failed)

        await pub.setFail(false)
        await model.run()
        #expect(model.phase == .ready)
    }
}
