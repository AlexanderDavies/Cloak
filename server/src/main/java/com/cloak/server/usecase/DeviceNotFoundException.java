package com.cloak.server.usecase;

/**
 * Thrown by {@link FetchPreKeyBundleUseCase} when no device is registered for the requested owner
 * subject. Maps to HTTP 404 via {@code GlobalExceptionHandler}. The message is a safe static string
 * — the owner sub is never included (privacy: no PII in wire responses, root CLAUDE.md §0.6).
 */
public class DeviceNotFoundException extends RuntimeException {

  /** Constructs with a safe, static message. */
  public DeviceNotFoundException() {
    super("Device not found.");
  }
}
