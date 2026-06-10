package com.cloak.server.adapter.input.websocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks open WebSocket sessions keyed by the connected user's Keycloak {@code sub}. Populated as
 * users connect (this task) and read by the outbound delivery consumer to fan a message out to a
 * recipient's live sessions (Task 8).
 */
@Component
public class WebSocketSessionRegistry {

  private final ConcurrentHashMap<String, Set<WebSocketSession>> bySub = new ConcurrentHashMap<>();

  /**
   * Registers an open session for a connected user.
   *
   * @param sub the user's Keycloak {@code sub}
   * @param session the open session to track
   */
  public void add(String sub, WebSocketSession session) {
    bySub.computeIfAbsent(sub, k -> ConcurrentHashMap.newKeySet()).add(session);
  }

  /**
   * Drops a closed session for a user.
   *
   * @param sub the user's Keycloak {@code sub}
   * @param session the session to stop tracking
   */
  public void remove(String sub, WebSocketSession session) {
    Set<WebSocketSession> set = bySub.get(sub);
    if (set != null) {
      set.remove(session);
    }
  }

  /**
   * Returns the recipient's currently-open sessions.
   *
   * @param sub the recipient's Keycloak {@code sub}
   * @return the open sessions, or an empty set if none
   */
  public Set<WebSocketSession> sessionsFor(String sub) {
    return bySub.getOrDefault(sub, Set.of());
  }
}
