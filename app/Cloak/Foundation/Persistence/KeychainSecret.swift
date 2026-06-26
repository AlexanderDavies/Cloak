import Foundation
import Security

/// A persistent random secret in the Keychain (the SQLCipher passphrase). Hardware-protected,
/// never logged, never leaves the device.
enum KeychainSecret {
    /// Returns the secret for `account`, creating a 32-byte random one on first use.
    static func loadOrCreate(account: String) throws -> String {
        if let existing = try load(account: account) { return existing }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw KeychainError.random
        }
        let secret = Data(bytes).base64EncodedString()
        try store(account: account, secret: secret)
        return secret
    }

    enum KeychainError: Error { case random, status(OSStatus) }

    private static func load(account: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else { throw KeychainError.status(status) }
        return String(data: data, encoding: .utf8)
    }

    private static func store(account: String, secret: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(secret.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }
}
