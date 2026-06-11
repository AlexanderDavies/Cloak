package com.cloak.server.adapter.input.websocket;

import com.cloak.server.usecase.RouteMessageCommand;
import com.cloak.server.usecase.RouteMessageUseCase;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Base64;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticated inbound WebSocket adapter. The {@code /ws} HTTP upgrade is authenticated by the
 * resource-server filter chain, so {@link WebSocketSession#getPrincipal()} carries the {@link
 * JwtAuthenticationToken}. The sender is always derived from that validated token's {@code sub} —
 * never from the client frame (sender-spoofing trust rule, per the Phase 0 envelope contract).
 *
 * <p>The {@code ciphertext} is opaque base64; it is decoded only to pass the raw bytes to the use
 * case and is never logged or inspected (§0.6.4 privacy).
 */
@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {

  private final RouteMessageUseCase useCase;
  private final WebSocketSessionRegistry registry;
  private final JsonMapper jsonMapper;
  private final ObservationRegistry observationRegistry;

  /**
   * Creates the handler.
   *
   * @param useCase the routing use case (persist-then-publish)
   * @param registry the session registry, populated on connect for outbound delivery
   * @param jsonMapper the shared Jackson 3 mapper for parsing inbound frames
   * @param observationRegistry registry for the WS-ingest span (start of the message trace)
   */
  public MessageWebSocketHandler(
      RouteMessageUseCase useCase,
      WebSocketSessionRegistry registry,
      JsonMapper jsonMapper,
      ObservationRegistry observationRegistry) {
    this.useCase = useCase;
    this.registry = registry;
    this.jsonMapper = jsonMapper;
    this.observationRegistry = observationRegistry;
  }

  /** Extracts the authenticated Keycloak {@code sub} from the session's validated JWT principal. */
  private String sub(WebSocketSession session) {
    // The /ws handshake is authenticated() by the resource-server chain, so the principal is always
    // a JwtAuthenticationToken — guard defensively so a future config change fails clearly here
    // rather than as an opaque ClassCastException.
    if (session.getPrincipal() instanceof JwtAuthenticationToken auth) {
      return auth.getToken().getSubject();
    }
    throw new IllegalStateException("WebSocket session has no authenticated JWT principal");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    registry.add(sub(session), session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    registry.remove(sub(session), session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    // Open the message trace at the WS hop so the whole path (route → JDBC → Kafka → delivery)
    // forms one connected trace. Never attach ciphertext or message content (§0.6.4 privacy).
    Observation.createNotStarted("cloak.ws.ingest", observationRegistry)
        .observe(
            () -> {
              InboundEnvelope env =
                  jsonMapper.readValue(message.getPayload(), InboundEnvelope.class);
              useCase.route(
                  new RouteMessageCommand(
                      env.messageId(),
                      sub(session), // sender = authenticated principal, never a client field
                      env.toSub(),
                      env.deviceId(),
                      Base64.getDecoder().decode(env.ciphertext())));
            });
  }
}
