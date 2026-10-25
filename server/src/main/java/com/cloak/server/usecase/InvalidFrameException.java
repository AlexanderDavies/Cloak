package com.cloak.server.usecase;

/**
 * Signals that an inbound frame failed use-case validation and was rejected <em>before</em>
 * anything was persisted or published.
 *
 * <p>Deliberately distinct from a bare {@link IllegalArgumentException}: the inbound adapter treats
 * this — and only this — as "frame dropped, nothing written". Any other failure (a malformed
 * message id reaching JDBC, a publisher fault after the row committed) must propagate rather than
 * be logged as a drop that did not happen.
 */
public final class InvalidFrameException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message routing-context-only reason; never carries a {@code sub}, ciphertext, or any
   *     message content (§0.6.4 privacy)
   */
  public InvalidFrameException(String message) {
    super(message);
  }
}
