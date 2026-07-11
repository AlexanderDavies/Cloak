package com.cloak.server.usecase;

import com.cloak.server.domain.identity.DirectoryUser;
import com.cloak.server.port.output.identity.UserDirectoryPort;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Component;

/**
 * Resolves an exact handle (email or username) to a {@link DirectoryUser}. Throws {@link
 * UserNotFoundException} when no exact match exists, mapping to HTTP 404. The handle is never used
 * as a metric label (privacy: no PII on metrics, root CLAUDE.md §0.6).
 */
@Component
public class LookupUserUseCase {

  private final UserDirectoryPort userDirectoryPort;

  /**
   * Creates the use case.
   *
   * @param userDirectoryPort the identity directory port (exact-match user search)
   */
  LookupUserUseCase(UserDirectoryPort userDirectoryPort) {
    this.userDirectoryPort = userDirectoryPort;
  }

  /**
   * Looks up a user by exact handle (email address or username).
   *
   * @param handle an exact email address or username; never logged or used as a metric label
   * @return the resolved user (carrying only the stable Keycloak {@code sub})
   * @throws UserNotFoundException when no user exactly matches the handle
   */
  @Observed(name = "cloak.users.lookup")
  public DirectoryUser lookup(String handle) {
    return userDirectoryPort.findExact(handle).orElseThrow(UserNotFoundException::new);
  }
}
