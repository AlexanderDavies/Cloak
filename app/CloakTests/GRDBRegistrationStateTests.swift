import Testing
import Foundation
import GRDB
@testable import Cloak

@Suite struct GRDBRegistrationStateTests {
    private func freshQueue() throws -> DatabaseQueue {
        let path = NSTemporaryDirectory() + "regstate-\(UUID().uuidString).sqlite"
        return try EncryptedDatabase.open(path: path, passphrase: "k")
    }

    @Test func defaultsToUnregistered() throws {
        let state = try GRDBRegistrationState(database: freshQueue())
        #expect(state.isRegistered() == false)
    }

    @Test func markRegistered_persistsAcrossFreshInstancesOnSameDb() throws {
        let queue = try freshQueue()
        let first = try GRDBRegistrationState(database: queue)
        first.markRegistered()
        #expect(first.isRegistered())

        // A fresh instance on the same DB sees the persisted flag (survives relaunch).
        let second = try GRDBRegistrationState(database: queue)
        #expect(second.isRegistered())
    }

    @Test func markRegistered_isIdempotent() throws {
        let state = try GRDBRegistrationState(database: freshQueue())
        state.markRegistered()
        state.markRegistered()
        #expect(state.isRegistered())
    }
}
