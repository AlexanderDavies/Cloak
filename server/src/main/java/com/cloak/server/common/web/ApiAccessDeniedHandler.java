package com.cloak.server.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emits the standard {@link WrappedResponse} envelope for 403 responses. Like {@link
 * ApiAuthenticationEntryPoint}, access-denied failures are raised in the security filter chain
 * before the dispatcher, so this handler serialises the same envelope (code {@code FORBIDDEN}).
 */
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

  private final JsonMapper jsonMapper;

  /** Creates the handler with the Boot-provided {@link JsonMapper} for envelope serialisation. */
  public ApiAccessDeniedHandler(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    SecurityErrorResponses.write(
        response,
        jsonMapper,
        HttpServletResponse.SC_FORBIDDEN,
        ApiError.of("FORBIDDEN", "Access denied"));
  }
}
