import Testing
import Foundation
import GRDB
import LibSignalClient
@testable import Cloak

/// Tests for `ChatThreadViewModel` — the integration of X3DH session setup, encrypt-on-send,
/// and decrypt-on-receive.
///
/// Wire setup: real `SessionEstablisher` + `MessageCrypto` over two temp SQLCipher `SignalKeyStore`s
/// (Alice + Bob), a `MockPreKeyBundleClient` returning Bob's bundle, and a `FakeMessageTransport`.
@Suite @MainActor struct ChatThreadViewModelTests {

    // MARK: - Participant setup

    /// All key material for a single test participant.
    private struct ParticipantSetup {
        let store: SignalKeyStore
        let address: ProtocolAddress
        let bundle: RemotePreKeyBundle
        let crypto: MessageCrypto
    }

    /// Creates a fresh SignalKeyStore with all prekeys stored and the matching public bundle.
    private func makeParticipant(sub: String) throws -> ParticipantSetup {
        let path = NSTemporaryDirectory() + "ct-\(sub)-\(UUID().uuidString).sqlite"
        let queue = try EncryptedDatabase.open(path: path, passphrase: "k")
        let identity = IdentityKeyPair.generate()
        let regId = UInt32.random(in: 1...0x3FFF)
        let store = try SignalKeyStore(database: queue, identity: identity, registrationId: regId)
        let address = try ProtocolAddress(name: sub, deviceId: 1)
        let timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        let ecPriv = PrivateKey.generate()
        let ecSig = identity.privateKey.generateSignature(message: ecPriv.publicKey.serialize())
        try store.storeSignedPreKey(
            try LibSignalClient.SignedPreKeyRecord(
                id: 1, timestamp: timestamp, privateKey: ecPriv, signature: ecSig),
            id: 1, context: NullContext())

        let otPriv = PrivateKey.generate()
        try store.storePreKey(
            try LibSignalClient.PreKeyRecord(id: 1, privateKey: otPriv),
            id: 1, context: NullContext())

        let kyberPair = KEMKeyPair.generate()
        let kyberSig = identity.privateKey.generateSignature(message: kyberPair.publicKey.serialize())
        try store.storeKyberPreKey(
            try KyberPreKeyRecord(id: 1, timestamp: timestamp, keyPair: kyberPair, signature: kyberSig),
            id: 1, context: NullContext())

        let bundle = RemotePreKeyBundle(
            registrationId: regId,
            deviceId: 1,
            identityKey: identity.identityKey.serialize().base64EncodedString(),
            signedPreKey: RemotePreKeyBundle.SignedPreKey(
                keyId: 1,
                publicKey: ecPriv.publicKey.serialize().base64EncodedString(),
                signature: ecSig.base64EncodedString()),
            oneTimePreKey: RemotePreKeyBundle.OneTimePreKey(
                keyId: 1,
                publicKey: otPriv.publicKey.serialize().base64EncodedString()),
            kyberPreKey: RemotePreKeyBundle.KyberPreKey(
                keyId: 1,
                publicKey: kyberPair.publicKey.serialize().base64EncodedString(),
                signature: kyberSig.base64EncodedString()))

        let crypto = MessageCrypto(store: store, localAddress: address)
        return ParticipantSetup(store: store, address: address, bundle: bundle, crypto: crypto)
    }

    /// Builds Alice's ChatThreadViewModel (recipient = "bob").
    private func makeViewModel(
        alice: ParticipantSetup,
        bob: ParticipantSetup,
        transport: FakeMessageTransport,
        bundleClient: MockPreKeyBundleClient
    ) -> ChatThreadViewModel {
        let establisher = SessionEstablisher(store: alice.store, localAddress: alice.address)
        return ChatThreadViewModel(
            recipient: ResolvedRecipient(sub: "bob", deviceId: 1),
            myDeviceId: 1,
            bundleClient: bundleClient,
            establisher: establisher,
            crypto: alice.crypto,
            transport: transport,
            tokenProvider: { "test-token" })
    }

    // MARK: - send() tests

    /// send("hi") on a fresh thread: fetches the bundle once, produces a .preKey envelope with
    /// correct addressing, and appends a mine=true bubble.
    @Test func sendFirstMessage_preKeyEnvelope_mineBubbleAppended() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        await viewModel.send("hi")

        #expect(bundleClient.fetchCount == 1, "Bundle should be fetched exactly once on first send")
        #expect(viewModel.error == nil)
        #expect(viewModel.bubbles.count == 1)

        let bubble = try #require(viewModel.bubbles.first)
        #expect(bubble.text == "hi")
        #expect(bubble.isMine)
        #expect(!bubble.id.isEmpty)

        let env = try #require(transport.sentEnvelopes.first)
        #expect(env.messageType == .preKey, "First message to a new peer must be a preKey message")
        #expect(env.toSub == "bob")
        #expect(env.toDeviceId == 1)
        #expect(env.fromDeviceId == 1)
        #expect(!env.ciphertext.isEmpty)
        #expect(Data(base64Encoded: env.ciphertext) != nil, "Ciphertext must be valid base64")
    }

    /// A second send() does NOT re-fetch the bundle (session already established).
    @Test func secondSend_doesNotReFetchBundle_bubbleAppended() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        await viewModel.send("hi")
        await viewModel.send("again")

        #expect(bundleClient.fetchCount == 1, "Bundle must be fetched exactly once across multiple sends")
        #expect(viewModel.bubbles.count == 2)
        #expect(viewModel.bubbles[0].text == "hi")
        #expect(viewModel.bubbles[1].text == "again")
        #expect(viewModel.bubbles[1].isMine)
        #expect(viewModel.error == nil)
        #expect(transport.sentEnvelopes.count == 2)
    }

    /// Two concurrent first-sends must establish the session only once (one bundle fetch), not race
    /// into two fetches + two X3DH runs (which would burn an extra one-time prekey).
    @Test func concurrentFirstSends_fetchBundleOnce() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        async let first: Void = viewModel.send("hi")
        async let second: Void = viewModel.send("yo")
        _ = await (first, second)

        #expect(bundleClient.fetchCount == 1, "Concurrent first-sends must fetch the bundle exactly once")
        #expect(viewModel.bubbles.count == 2)
        #expect(viewModel.error == nil)
        #expect(transport.sentEnvelopes.count == 2)
    }

    /// A new view model over a store that already holds a session (e.g. a re-entered thread) must
    /// reuse it — no bundle fetch, no re-run of X3DH.
    @Test func send_reusesExistingStoreSession_withoutFetching() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")

        // Pre-establish a session in Alice's store, as a prior thread visit would have.
        let establisher = SessionEstablisher(store: alice.store, localAddress: alice.address)
        try establisher.establishOutbound(with: bob.bundle, recipientSub: "bob")

        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        await viewModel.send("again")

        #expect(bundleClient.fetchCount == 0, "An existing store session must be reused, not re-fetched")
        #expect(viewModel.bubbles.count == 1)
        #expect(viewModel.error == nil)
        #expect(transport.sentEnvelopes.count == 1)
    }

    // MARK: - receive() tests

    /// An inbound .preKey frame (Bob encrypted for Alice using X3DH with Alice's bundle)
    /// is decrypted and appended as a non-mine bubble.
    @Test func inboundPreKeyFrame_decryptedAndBubbleAppended() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        // Bob runs X3DH against Alice's bundle to get an outbound session, then encrypts.
        let bobEstablisher = SessionEstablisher(store: bob.store, localAddress: bob.address)
        try bobEstablisher.establishOutbound(with: alice.bundle, recipientSub: "alice")
        let bobPayload = try bob.crypto.encrypt(Data("hello from bob".utf8), to: "alice", deviceId: 1)
        #expect(bobPayload.type == .preKey)

        let inboundEnv = MessageEnvelope(
            messageId: "inbound-1",
            toSub: "alice",
            toDeviceId: 1,
            fromDeviceId: 1,
            messageType: bobPayload.type,
            ciphertext: bobPayload.ciphertext.base64EncodedString())

        await viewModel.receive(inboundEnv)

        #expect(viewModel.bubbles.count == 1)
        let bubble = try #require(viewModel.bubbles.first)
        #expect(bubble.text == "hello from bob")
        #expect(!bubble.isMine)
        #expect(bubble.id == "inbound-1")
        #expect(viewModel.error == nil)
    }

    /// A decrypt failure (garbage ciphertext) surfaces on self.error and does not crash.
    @Test func decryptFailure_setsError_doesNotCrash() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        // Valid base64, but the decoded bytes are not a valid Signal message — decrypt throws.
        let garbled = Data("notavalidciphertextXXXXXXXXXXXXXXXXX".utf8).base64EncodedString()
        let garbageEnv = MessageEnvelope(
            messageId: "garbage-1",
            toSub: "alice",
            toDeviceId: 1,
            fromDeviceId: 1,
            messageType: .preKey,
            ciphertext: garbled)

        await viewModel.receive(garbageEnv)

        #expect(viewModel.bubbles.isEmpty, "No bubble should be appended when decrypt fails")
        #expect(viewModel.error != nil, "Decrypt failure must surface on self.error (never silently dropped)")
    }

    /// A frame whose server-stamped fromSub is NOT this thread's peer belongs to another
    /// conversation; it must be ignored here, not mis-decrypted against the peer's session.
    @Test func inboundFrameFromOtherSender_isIgnored() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        let transport = FakeMessageTransport()
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        // A well-formed-looking frame from "charlie" (not the thread's peer "bob").
        let foreignEnv = MessageEnvelope(
            messageId: "foreign-1",
            toSub: "alice",
            toDeviceId: 1,
            fromDeviceId: 1,
            messageType: .preKey,
            ciphertext: Data("irrelevant".utf8).base64EncodedString(),
            fromSub: "charlie")

        await viewModel.receive(foreignEnv)

        #expect(viewModel.bubbles.isEmpty, "Frames from other senders must not appear in this thread")
        #expect(viewModel.error == nil, "A frame for another conversation is not an error")
    }

    // MARK: - start() tests

    /// start() subscribes to the transport stream; a terminal stream error surfaces on self.error.
    @Test func start_streamError_setsError() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")
        enum TestError: Error { case networkFailure }
        let transport = FakeMessageTransport(inboundError: TestError.networkFailure)
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        await viewModel.start()

        #expect(viewModel.error != nil, "Terminal stream error must surface on self.error")
        #expect(viewModel.bubbles.isEmpty)
    }

    /// start() routes each inbound envelope through receive() in order.
    ///
    /// The stream is pre-built with Bob's two encrypted frames (preKey + normal ratchet).
    /// `await viewModel.start()` blocks until the pre-built stream finishes — fully deterministic,
    /// no Task.yield() counts, no continuation race (avoids the C7 deadlock class).
    @Test func start_inboundFrames_appendBubblesInOrder() async throws {
        let alice = try makeParticipant(sub: "alice")
        let bob = try makeParticipant(sub: "bob")

        // Bob runs X3DH with Alice's bundle then encrypts two messages (preKey + normal ratchet).
        let bobEstablisher = SessionEstablisher(store: bob.store, localAddress: bob.address)
        try bobEstablisher.establishOutbound(with: alice.bundle, recipientSub: "alice")
        let payload1 = try bob.crypto.encrypt(Data("msg1".utf8), to: "alice", deviceId: 1)
        let payload2 = try bob.crypto.encrypt(Data("msg2".utf8), to: "alice", deviceId: 1)

        let env1 = MessageEnvelope(
            messageId: "s-1", toSub: "alice", toDeviceId: 1, fromDeviceId: 1,
            messageType: payload1.type, ciphertext: payload1.ciphertext.base64EncodedString())
        let env2 = MessageEnvelope(
            messageId: "s-2", toSub: "alice", toDeviceId: 1, fromDeviceId: 1,
            messageType: payload2.type, ciphertext: payload2.ciphertext.base64EncodedString())

        let transport = FakeMessageTransport(inboundFrames: [env1, env2])
        let bundleClient = MockPreKeyBundleClient(bundle: bob.bundle)
        let viewModel = makeViewModel(alice: alice, bob: bob, transport: transport, bundleClient: bundleClient)

        // start() returns after the pre-built stream finishes — all frames are processed.
        await viewModel.start()

        #expect(viewModel.bubbles.count == 2)
        #expect(viewModel.bubbles[0].text == "msg1")
        #expect(viewModel.bubbles[1].text == "msg2")
        #expect(viewModel.bubbles[0].isMine == false)
        #expect(viewModel.bubbles[1].isMine == false)
        #expect(viewModel.error == nil)
    }
}

// MARK: - FakeMessageTransport

/// Deterministic fake: pre-queues inbound frames in the stream at construction time (no continuation
/// capture needed). All yields and the finish signal are enqueued synchronously in the stream's
/// initializer closure, so iterating the stream is fully deterministic. Captures sent envelopes for
/// inspection. Avoids the C7-class deadlock.
private final class FakeMessageTransport: MessageTransport, @unchecked Sendable {
    private(set) var sentEnvelopes: [MessageEnvelope] = []
    private let inboundFrames: [MessageEnvelope]
    private let inboundError: Error?

    init(inboundFrames: [MessageEnvelope] = [], inboundError: Error? = nil) {
        self.inboundFrames = inboundFrames
        self.inboundError = inboundError
    }

    func connect(accessToken: String) async throws {}

    func send(_ envelope: MessageEnvelope) async throws {
        sentEnvelopes.append(envelope)
    }

    func inbound() async -> AsyncThrowingStream<MessageEnvelope, Error> {
        let frames = inboundFrames
        let error = inboundError
        return AsyncThrowingStream { continuation in
            for frame in frames { continuation.yield(frame) }
            continuation.finish(throwing: error)
        }
    }

    func disconnect() async {}
}

// MARK: - MockPreKeyBundleClient

/// Returns a pre-configured bundle immediately and counts fetch invocations.
private final class MockPreKeyBundleClient: PreKeyBundleClient, @unchecked Sendable {
    let bundle: RemotePreKeyBundle
    private(set) var fetchCount = 0

    init(bundle: RemotePreKeyBundle) { self.bundle = bundle }

    func fetchBundle(sub: String, accessToken: String) async throws -> RemotePreKeyBundle {
        fetchCount += 1
        return bundle
    }
}
