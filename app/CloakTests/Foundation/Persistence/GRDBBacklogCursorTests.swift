import Foundation
import GRDB
import Testing
@testable import Cloak

@Suite struct GRDBBacklogCursorTests {
    private func makeStore() throws -> GRDBBacklogCursor {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        let database = try EncryptedDatabase.open(path: path, passphrase: "test-pass")
        return try GRDBBacklogCursor(database: database)
    }

    @Test func startsNilThenPersistsAdvances() throws {
        let store = try makeStore()
        #expect(store.highWaterMark() == nil)
        store.advance(to: "cursor-1")
        #expect(store.highWaterMark() == "cursor-1")
    }

    @Test func advanceOverwritesPreviousCursor() throws {
        let store = try makeStore()
        store.advance(to: "cursor-1")
        store.advance(to: "cursor-2")
        #expect(store.highWaterMark() == "cursor-2")
    }
}
