package com.cloak.server.adapter.input.rest.messages;

import com.cloak.server.common.web.WrappedResponse;
import com.cloak.server.usecase.FetchMessageHistoryUseCase;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless keyset-paginated pull of the caller's undelivered message backlog — the backstop for
 * offline recipients when the real-time {@code /ws} push was missed. The recipient is always the
 * authenticated JWT {@code sub}; there is no caller-supplied recipient parameter, which
 * structurally prevents pulling another user's messages ({@code
 * docs/contracts/slice3-message-history.md}).
 */
@RestController
public class MessageHistoryController {

  private final FetchMessageHistoryUseCase useCase;

  MessageHistoryController(FetchMessageHistoryUseCase useCase) {
    this.useCase = useCase;
  }

  /**
   * Returns one page of the authenticated caller's backlog.
   *
   * @param jwt the validated Keycloak JWT (recipient {@code sub} extracted server-side)
   * @param cursor the opaque cursor to resume after, or absent to start from the beginning
   * @param limit the requested page size; defaults to 50, capped at 200
   * @return 200 with the page, or 400 if {@code cursor} is malformed
   */
  @GetMapping("/v1/messages")
  WrappedResponse<MessageHistoryResponse> history(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    var page = useCase.fetch(jwt.getSubject(), cursor, limit);
    return WrappedResponse.ok(MessageHistoryResponse.from(page));
  }
}
