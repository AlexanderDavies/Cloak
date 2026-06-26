import Foundation
import GRDB

/// GRDB/SQLCipher-backed ``RegistrationState`` over the encrypted ``EncryptedDatabase`` queue.
///
/// Tracks the *published* flag — set only after the public bundle reaches the server — which is
/// deliberately separate from the vault's `hasIdentity()` (publish can fail after the private keys
/// are persisted, so the device stays "unregistered" and the next launch retries the publish).
/// Stored as a single-row flag table; `markRegistered()` upserts the flag to `true`.
struct GRDBRegistrationState: RegistrationState {
    private let database: DatabaseQueue

    init(database: DatabaseQueue) throws {
        self.database = database
        try database.write { conn in
            try conn.execute(sql: """
                CREATE TABLE IF NOT EXISTS device_registration(
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    registered INTEGER NOT NULL DEFAULT 0
                )
                """)
        }
    }

    func isRegistered() -> Bool {
        // A read failure resolves to `false` so a transient DB issue retries registration rather
        // than skipping it (fail-safe toward re-publishing, never toward silently un-registered).
        (try? database.read { conn in
            try Bool.fetchOne(conn, sql: "SELECT registered FROM device_registration WHERE id = 1") ?? false
        }) ?? false
    }

    func markRegistered() {
        // Best-effort: a failed write leaves the device unregistered, so the next launch retries.
        try? database.write { conn in
            try conn.execute(sql: """
                INSERT INTO device_registration(id, registered) VALUES (1, 1)
                ON CONFLICT(id) DO UPDATE SET registered = 1
                """)
        }
    }
}
