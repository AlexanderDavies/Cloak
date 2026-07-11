package com.cloak.server.usecase;

/**
 * Command to route one message: routing metadata plus the opaque ciphertext. {@code senderSub} is
 * always the authenticated principal — never a client-supplied field (sender-spoofing trust rule).
 * {@code senderDeviceId} and {@code recipientDeviceId} are libsignal integer device numbers. {@code
 * messageType} discriminates the decrypt path: {@code 2} = normal SignalMessage, {@code 3} =
 * PreKeySignalMessage.
 */
public record RouteMessageCommand(
    String messageId,
    String senderSub,
    String recipientSub,
    int senderDeviceId,
    int recipientDeviceId,
    int messageType,
    byte[] ciphertext) {}
