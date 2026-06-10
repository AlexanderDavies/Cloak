package com.cloak.server.usecase;

/**
 * Command to route one message: routing metadata plus the opaque ciphertext. {@code senderSub} is
 * always the authenticated principal — never a client-supplied field (sender-spoofing trust rule).
 */
public record RouteMessageCommand(
    String messageId, String senderSub, String recipientSub, String deviceId, byte[] ciphertext) {}
