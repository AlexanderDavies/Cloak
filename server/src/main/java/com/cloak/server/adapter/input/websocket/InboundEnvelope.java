package com.cloak.server.adapter.input.websocket;

/**
 * Inbound WebSocket frame. {@code ciphertext} is base64-encoded opaque bytes — the server never
 * decrypts it. There is intentionally no {@code fromSub} field: the sender is the authenticated
 * principal, never a client-supplied value (sender-spoofing trust rule). {@code toDeviceId} and
 * {@code fromDeviceId} are libsignal integer device numbers (always {@code 1} this slice). {@code
 * messageType} discriminates the decrypt path: {@code 2} = normal SignalMessage, {@code 3} =
 * PreKeySignalMessage. {@code conversationId} is client-supplied but validated, not trusted: the
 * server recomputes it from the authenticated sender and {@code toSub} and drops the frame on
 * mismatch (see {@code docs/contracts/slice3-message-envelope.md}).
 */
public record InboundEnvelope(
    String messageId,
    String toSub,
    int toDeviceId,
    int fromDeviceId,
    int messageType,
    String conversationId,
    String ciphertext) {}
