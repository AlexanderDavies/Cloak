import Foundation
import GRDB

/// GRDB/SQLCipher-backed ``MessageRepository`` — the authoritative local message history (guide §6, §7).
///
/// Double-Ratchet decryption is destructive: once a ciphertext is decrypted the ratchet advances and
/// it can never be decrypted again, so this table (not the server's ciphertext queue) is the only
/// source of truth for history. `save` upserts on `message_id` so the same message arriving twice —
/// once live, once via backlog pull — never double-renders.
struct GRDBMessageRepository: MessageRepository {
    private let database: DatabaseQueue

    init(database: DatabaseQueue) throws {
        self.database = database
        try database.write { conn in
            try conn.execute(sql: """
                CREATE TABLE IF NOT EXISTS message(
                    message_id      TEXT PRIMARY KEY,
                    conversation_id TEXT    NOT NULL,
                    peer_sub        TEXT    NOT NULL,
                    is_mine         INTEGER NOT NULL,
                    sent_at         DOUBLE  NOT NULL,
                    body            TEXT    NOT NULL,
                    status          TEXT    NOT NULL
                )
                """)
            try conn.execute(sql:
                "CREATE INDEX IF NOT EXISTS idx_message_thread ON message(conversation_id, sent_at)")
        }
    }

    func save(_ message: StoredMessage) throws {
        try database.write { conn in
            try conn.execute(sql: """
                INSERT INTO message(message_id, conversation_id, peer_sub, is_mine, sent_at, body, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(message_id) DO NOTHING
                """, arguments: [message.messageId, message.conversationId, message.peerSub, message.isMine,
                                 message.sentAt.timeIntervalSince1970, message.body, message.status.rawValue])
        }
    }

    func contains(messageId: String) throws -> Bool {
        try database.read { conn in
            try Bool.fetchOne(conn, sql: "SELECT EXISTS(SELECT 1 FROM message WHERE message_id = ?)",
                              arguments: [messageId]) ?? false
        }
    }

    func updateStatus(messageId: String, status: MessageStatus) throws {
        try database.write { conn in
            try conn.execute(sql: "UPDATE message SET status = ? WHERE message_id = ?",
                             arguments: [status.rawValue, messageId])
        }
    }

    /// Fails every send left at `sending`. Call once at launch, before any send can start: a
    /// `sending` row then can only belong to a process that was killed mid-send, and nothing else
    /// will ever move it on — without this it shows "Sending…" forever.
    func failInterruptedSends() throws {
        try database.write { conn in
            try conn.execute(sql: "UPDATE message SET status = ? WHERE status = ?",
                             arguments: [MessageStatus.failed.rawValue, MessageStatus.sending.rawValue])
        }
    }

    func loadThread(conversationId: String) throws -> [StoredMessage] {
        try database.read { conn in
            try Row.fetchAll(conn, sql:
                "SELECT * FROM message WHERE conversation_id = ? ORDER BY sent_at, message_id",
                arguments: [conversationId]).map(Self.decode)
        }
    }

    func latestPerConversation() throws -> [StoredMessage] {
        try database.read { conn in
            try Row.fetchAll(conn, sql: """
                SELECT * FROM (
                    SELECT m.*, ROW_NUMBER() OVER (
                        PARTITION BY conversation_id
                        ORDER BY sent_at DESC, message_id DESC) AS rn
                    FROM message m
                )
                WHERE rn = 1
                ORDER BY sent_at DESC
                """).map(Self.decode)
        }
    }

    private static func decode(_ row: Row) -> StoredMessage {
        StoredMessage(
            messageId: row["message_id"], conversationId: row["conversation_id"],
            peerSub: row["peer_sub"], isMine: (row["is_mine"] as Int64) != 0,
            sentAt: Date(timeIntervalSince1970: row["sent_at"]), body: row["body"],
            status: MessageStatus(rawValue: row["status"]) ?? .sent)
    }
}
