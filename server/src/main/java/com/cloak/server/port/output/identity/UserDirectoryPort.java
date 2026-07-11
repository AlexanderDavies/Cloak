package com.cloak.server.port.output.identity;

import com.cloak.server.domain.identity.DirectoryUser;
import java.util.Optional;

/**
 * Output port: resolves an exact handle (email address or username) to a {@link DirectoryUser}.
 *
 * <p>Implementations must apply {@code exact=true} matching only — no prefix search, no listing, no
 * autocomplete. This is deliberate: the privacy-preserving contact-discovery design is a future
 * initiative; this port exposes the minimum needed to bootstrap a conversation (root CLAUDE.md §6).
 */
public interface UserDirectoryPort {

  /**
   * Finds a user whose email or username exactly matches {@code handle}.
   *
   * @param handle an exact email address or username; never logged or used as a metric label
   * @return the resolved user, or empty if no exact match exists
   */
  Optional<DirectoryUser> findExact(String handle);
}
