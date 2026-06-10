package com.cloak.server.adapter.input.rest.identity;

import com.cloak.server.common.web.WrappedResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Auth-probe endpoint: echoes the authenticated caller's subject from the validated JWT. */
@RestController
public class WhoAmIController {

  /** Response payload carrying the authenticated subject. */
  record MeResponse(String sub) {}

  @GetMapping("/v1/me")
  WrappedResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
    return WrappedResponse.ok(new MeResponse(jwt.getSubject()));
  }
}
