package com.cloak.server.adapter.input.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the inbound message handler at {@code /ws}. The HTTP upgrade is authenticated by the
 * resource-server filter chain (Task 2 {@code anyRequest().authenticated()}), so the negotiated
 * session's principal is the validated {@code JwtAuthenticationToken}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final MessageWebSocketHandler handler;

  /**
   * Creates the configurer.
   *
   * @param handler the authenticated inbound message handler to register at {@code /ws}
   */
  public WebSocketConfig(MessageWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/ws");
  }
}
