import Testing
import Foundation
@testable import Cloak

@Suite struct PublicKeyBundleTests {
    @Test func encodesContractShape() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 3)
        let bundle = try PublicKeyBundle(from: keys, deviceId: 1)
        let json = try bundle.jsonObject()      // [String: Any]
        #expect(json["registrationId"] as? Int == Int(keys.registrationId))
        #expect(json["deviceId"] as? Int == 1)
        #expect((json["oneTimePreKeys"] as? [[String: Any]])?.count == 3)
        let signed = json["signedPreKey"] as? [String: Any]
        #expect(signed?["signature"] != nil && signed?["publicKey"] != nil)
    }

    @Test func identityKeyIs33Bytes() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        let bundle = try PublicKeyBundle(from: keys, deviceId: 1)
        let json = try bundle.jsonObject()
        guard let b64 = json["identityKey"] as? String,
              let data = Data(base64Encoded: b64) else {
            Issue.record("identityKey is not valid base64")
            return
        }
        #expect(data.count == 33)
    }

    @Test func contractFieldNamesMatch() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 2)
        let bundle = try PublicKeyBundle(from: keys, deviceId: 1)
        let json = try bundle.jsonObject()
        // Top-level contract fields must be present by exact name
        #expect(json["registrationId"] != nil)
        #expect(json["deviceId"] != nil)
        #expect(json["identityKey"] != nil)
        #expect(json["signedPreKey"] != nil)
        #expect(json["oneTimePreKeys"] != nil)
        // signedPreKey nested fields
        let spk = json["signedPreKey"] as? [String: Any]
        #expect(spk?["keyId"] != nil)
        #expect(spk?["publicKey"] != nil)
        #expect(spk?["signature"] != nil)
        // oneTimePreKeys nested fields
        let otks = json["oneTimePreKeys"] as? [[String: Any]]
        #expect(otks?.first?["keyId"] != nil)
        #expect(otks?.first?["publicKey"] != nil)
    }

    @Test func deviceIdRoundTrips() throws {
        let keys = try SignalKeyGenerator.generate(oneTimeCount: 1)
        let bundle = try PublicKeyBundle(from: keys, deviceId: 7)
        #expect(bundle.deviceId == 7)
        let json = try bundle.jsonObject()
        #expect(json["deviceId"] as? Int == 7)
    }
}
