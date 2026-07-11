package com.cloak.server.adapter.input.rest.users;

import com.cloak.server.common.web.WrappedResponse;
import com.cloak.server.domain.identity.DirectoryUser;
import com.cloak.server.usecase.LookupUserUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves an exact handle (email or username) to the recipient's Keycloak {@code sub} and device
 * number, so a sender can address an X3DH key-bundle fetch. Exact-match only — no prefix search, no
 * listing, no autocomplete (privacy: root CLAUDE.md §0.6).
 */
@RestController
@RequestMapping("/v1/users")
public class UserLookupController {

  private final LookupUserUseCase useCase;

  /**
   * Creates the controller.
   *
   * @param useCase the user lookup use case
   */
  UserLookupController(LookupUserUseCase useCase) {
    this.useCase = useCase;
  }

  /**
   * Looks up a user by exact email address or username.
   *
   * @param handle the exact email address or username to resolve; never logged or included in
   *     metrics
   * @return {@code 200} with {@code { sub, deviceId:1 }}, or {@code 404} if no exact match
   */
  @GetMapping("/lookup")
  WrappedResponse<UserLookupResponse> lookup(@RequestParam String handle) {
    DirectoryUser user = useCase.lookup(handle);
    return WrappedResponse.ok(new UserLookupResponse(user.sub(), 1));
  }
}
