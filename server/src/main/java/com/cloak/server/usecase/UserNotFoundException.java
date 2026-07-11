package com.cloak.server.usecase;

/**
 * Thrown by {@link LookupUserUseCase} when no user matches the requested handle exactly. Maps to
 * HTTP 404 via {@code GlobalExceptionHandler}. The message is a safe static string — the handle is
 * never included (privacy: no PII in wire responses, root CLAUDE.md §0.6).
 */
public class UserNotFoundException extends RuntimeException {

  /** Constructs with a safe, static message. */
  public UserNotFoundException() {
    super("User not found.");
  }
}
