import Testing
import Foundation
@testable import Cloak

/// Privacy gate: proves that the JSON uploaded to the server contains only public key material.
///
/// This is a guard test — `PublicKeyBundle` is `Codable` and only declares public fields, so it
/// should always pass. Its value is as a regression barrier: if a private field is ever accidentally
/// added to `PublicKeyBundle` (or `GeneratedDeviceKeys` is incorrectly serialised directly), this
/// test catches it before anything reaches the wire.
///
/// Root CLAUDE.md principle 6 + docs/contracts/slice1-device-key-bundle.md: "The bundle is public
/// key material only — private keys never leave the device."
@Suite struct PrivacyGateTests {

    // MARK: - Field-name assertions

    @Test func uploadedBundleContainsOnlyPublicFields() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 2)
        let data = try JSONEncoder().encode(PublicKeyBundle(from: keys, deviceId: 1))
        let json = String(bytes: data, encoding: .utf8) ?? ""

        // The contract's public fields are present.
        #expect(json.contains("identityKey") && json.contains("signedPreKey"))

        // No field named "private" or "secret" (case-insensitive) appears in the upload JSON.
        #expect(!json.lowercased().contains("private"))
        #expect(!json.lowercased().contains("secret"))
    }

    // MARK: - Strengthened assertion: private key bytes are absent from the wire payload

    /// Encodes the private key for each key type as base64 and verifies none of those byte
    /// sequences appear in the serialised bundle. This catches the case where a private key's
    /// *value* leaks even if the field name looks innocuous (e.g. a field named "key" that
    /// accidentally carries the private bytes).
    @Test func privateKeyBytesAreAbsentFromUploadJSON() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 3)
        let data = try JSONEncoder().encode(PublicKeyBundle(from: keys, deviceId: 1))
        let json = String(bytes: data, encoding: .utf8) ?? ""

        // Collect base64 representations of every private key in the bundle.
        var privateKeyB64s: [String] = []

        // Identity private key.
        privateKeyB64s.append(keys.identityKeyPair.privateKey.serialize().base64EncodedString())

        // Signed prekey private key.
        privateKeyB64s.append(keys.signedPreKey.keyPair.serialize().base64EncodedString())

        // One-time prekey private keys.
        for otp in keys.oneTimePreKeys {
            privateKeyB64s.append(otp.keyPair.serialize().base64EncodedString())
        }

        // None of the private key byte sequences appear in the upload JSON.
        for b64 in privateKeyB64s {
            #expect(
                !json.contains(b64),
                "Private key bytes (\(b64.prefix(12))…) found in upload JSON"
            )
        }
    }
}
