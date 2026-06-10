package com.cloak.server.adapter.input.websocket;

import com.cloak.server.usecase.RouteMessageCommand;
import com.cloak.server.usecase.RouteMessageUseCase;
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

  /**
   * Creates the handler.
   *
   * @param useCase the routing use case (persist-then-publish)
   * @param registry the session registry, populated on connect for outbound delivery
   * @param jsonMapper the shared Jackson 3 mapper for parsing inbound frames
   */
  public MessageWebSocketHandler(
      RouteMessageUseCase useCase, WebSocketSessionRegistry registry, JsonMapper jsonMapper) {
    this.useCase = useCase;
    this.registry = registry;
    this.jsonMapper = jsonMapper;
  }

  /** Extracts the authenticated Keycloak {@code sub} from the session's validated JWT principal. */
  private String sub(WebSocketSession session) {
    JwtAuthenticationToken auth = (JwtAuthenticationToken) session.getPrincipal();
    return auth.getToken().getSubject();
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
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    InboundEnvelope env = jsonMapper.readValue(message.getPayload(), InboundEnvelope.class);
    useCase.route(
        new RouteMessageCommand(
            env.messageId(),
            sub(session), // sender = authenticated principal, never a client-supplied field
            env.toSub(),
            env.deviceId(),
            Base64.getDecoder().decode(env.ciphertext())));
  }
}
