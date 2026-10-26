import Testing
@testable import Cloak

struct ConversationKeyTests {
    @Test func matchesCanonicalSha256AndIsOrderIndependent() throws {
        let subA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        let subB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        let expected = "32eaa687f333efc245cc57c857f22ca5fb0e4757ea0a3c147ee2b6da131f77d0"
        #expect(ConversationKey.of([subA, subB]) == expected)
        #expect(ConversationKey.of([subB, subA]) == expected)
    }
}
