package com.cloak.server.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emits the standard {@link WrappedResponse} envelope for 401 responses. Spring Security raises
 * authentication failures in the filter chain before the dispatcher, so {@link
 * GlobalExceptionHandler} never sees them — this entry point serialises the same envelope (code
 * {@code UNAUTHORIZED}).
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final JsonMapper jsonMapper;

  /**
   * Creates the entry point with the Boot-provided {@link JsonMapper} for envelope serialisation.
   */
  public ApiAuthenticationEntryPoint(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    SecurityErrorResponses.write(
        response,
        jsonMapper,
        HttpServletResponse.SC_UNAUTHORIZED,
        ApiError.of("UNAUTHORIZED", "Authentication required"));
  }
}
