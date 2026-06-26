import Testing
import Foundation
import GRDB
@testable import Cloak

@Suite struct EncryptedDatabaseTests {

    // MARK: - EncryptedDatabase.open round-trip (proves SQLCipher wiring)

    @Test func opensEncryptedDb_andRoundTrips() throws {
        let path = NSTemporaryDirectory() + "cloak-test-\(UUID().uuidString).sqlite"
        defer { try? FileManager.default.removeItem(atPath: path) }
        let queue = try EncryptedDatabase.open(path: path, passphrase: "test-passphrase")
        try queue.write { try $0.execute(sql: "CREATE TABLE msg(val TEXT)")
                          try $0.execute(sql: "INSERT INTO msg VALUES ('hi')") }
        let result = try queue.read { try String.fetchOne($0, sql: "SELECT val FROM msg") }
        #expect(result == "hi")
    }

    // MARK: - KeychainSecret — fetch-or-create is idempotent

    @Test func keychainSecret_loadOrCreate_isIdempotent() throws {
        let account = "cloak.test.\(UUID().uuidString)"
        let first = try KeychainSecret.loadOrCreate(account: account)
        let second = try KeychainSecret.loadOrCreate(account: account)
        #expect(first == second)
        #expect(!first.isEmpty)
    }

    @Test func keychainSecret_producesDistinctSecretsForDistinctAccounts() throws {
        let accountA = "cloak.test.a.\(UUID().uuidString)"
        let accountB = "cloak.test.b.\(UUID().uuidString)"
        let secretA = try KeychainSecret.loadOrCreate(account: accountA)
        let secretB = try KeychainSecret.loadOrCreate(account: accountB)
        #expect(secretA != secretB)
    }

    // MARK: - EncryptedDatabase.openDefault uses Keychain-held passphrase

    @Test func openDefault_createsAccessibleDatabase() throws {
        // openDefault() uses the app support dir + Keychain. On the simulator the Keychain is
        // accessible, so this exercises the full path including KeychainSecret.loadOrCreate.
        let queue = try EncryptedDatabase.openDefault()
        let result = try queue.read { try Int.fetchOne($0, sql: "SELECT 1") }
        #expect(result == 1)
    }
}
