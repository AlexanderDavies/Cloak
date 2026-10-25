import Foundation
import GRDB

protocol BacklogCursorStore: Sendable {
    func highWaterMark() -> String?
    func advance(to cursor: String)
}

/// Single-row store of the last successfully-consumed backlog cursor.
///
/// `BacklogSync` advances the mark only past messages it has decrypted and persisted, never ahead of
/// them: Double-Ratchet decryption is destructive, so skipping an unconsumed message loses it forever,
/// while losing the mark itself just costs a harmless re-pull (the message repository dedupes on
/// `message_id`). That asymmetry is why a read failure resolves to `nil` rather than throwing or
/// guessing — fail-safe toward re-pulling, never toward skipping.
struct GRDBBacklogCursor: BacklogCursorStore {
    private let database: DatabaseQueue

    init(database: DatabaseQueue) throws {
        self.database = database
        try database.write { conn in
            try conn.execute(sql: """
                CREATE TABLE IF NOT EXISTS backlog_cursor(
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    cursor TEXT NOT NULL
                )
                """)
        }
    }

    func highWaterMark() -> String? {
        (try? database.read { conn in
            try String.fetchOne(conn, sql: "SELECT cursor FROM backlog_cursor WHERE id = 1")
        }) ?? nil
    }

    func advance(to cursor: String) {
        try? database.write { conn in
            try conn.execute(sql: """
                INSERT INTO backlog_cursor(id, cursor) VALUES (1, ?)
                ON CONFLICT(id) DO UPDATE SET cursor = excluded.cursor
                """, arguments: [cursor])
        }
    }
}
