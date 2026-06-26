import Foundation
import GRDB

/// Opens a SQLCipher-encrypted GRDB database (guide §6). Holds all private key material at rest.
enum EncryptedDatabase {
    /// Opens (creating if needed) an encrypted DB at `path` using `passphrase` as the SQLCipher key.
    static func open(path: String, passphrase: String) throws -> DatabaseQueue {
        var config = Configuration()
        config.prepareDatabase { conn in
            try conn.usePassphrase(passphrase)
        }
        return try DatabaseQueue(path: path, configuration: config)
    }

    /// Default on-device location + Keychain-held passphrase.
    static func openDefault() throws -> DatabaseQueue {
        let dir = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let passphrase = try KeychainSecret.loadOrCreate(account: "cloak.db.key")
        return try open(path: dir.appendingPathComponent("cloak.sqlite").path, passphrase: passphrase)
    }
}
