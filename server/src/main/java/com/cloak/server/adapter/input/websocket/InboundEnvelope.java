package com.cloak.server.adapter.input.websocket;

/**
 * Inbound WebSocket frame from docs/contracts/phase0-message-envelope.md. {@code ciphertext} is
 * base64-encoded opaque bytes — the server never decrypts it. There is intentionally no {@code
 * fromSub} field: the sender is the authenticated principal, never a client-supplied value
 * (sender-spoofing trust rule).
 */
public record InboundEnvelope(String messageId, String toSub, String deviceId, String ciphertext) {}
