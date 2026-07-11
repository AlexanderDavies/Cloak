package com.cloak.server.adapter.input.websocket;

/**
 * Inbound WebSocket frame. {@code ciphertext} is base64-encoded opaque bytes — the server never
 * decrypts it. There is intentionally no {@code fromSub} field: the sender is the authenticated
 * principal, never a client-supplied value (sender-spoofing trust rule). {@code toDeviceId} and
 * {@code fromDeviceId} are libsignal integer device numbers (always {@code 1} this slice). {@code
 * messageType} discriminates the decrypt path: {@code 2} = normal SignalMessage, {@code 3} =
 * PreKeySignalMessage.
 */
public record InboundEnvelope(
    String messageId,
    String toSub,
    int toDeviceId,
    int fromDeviceId,
    int messageType,
    String ciphertext) {}
