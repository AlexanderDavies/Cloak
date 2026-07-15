package com.cloak.server.adapter.input.rest.keys;

import com.cloak.server.common.web.WrappedResponse;
import com.cloak.server.usecase.FetchPreKeyBundleUseCase;
import com.cloak.server.usecase.RegisterDeviceKeysCommand;
import com.cloak.server.usecase.RegisterDeviceKeysUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages device public prekey bundles: publishes the calling device's bundle (Slice 1) and fetches
 * a recipient's bundle for X3DH key agreement (Slice 2).
 */
@RestController
public class DeviceKeyController {
  private final RegisterDeviceKeysUseCase registerDeviceKeysUseCase;
  private final FetchPreKeyBundleUseCase fetchUseCase;

  DeviceKeyController(
      RegisterDeviceKeysUseCase registerDeviceKeysUseCase, FetchPreKeyBundleUseCase fetchUseCase) {
    this.registerDeviceKeysUseCase = registerDeviceKeysUseCase;
    this.fetchUseCase = fetchUseCase;
  }

  /**
   * Upserts the authenticated device's public prekey bundle.
   *
   * @param jwt the validated Keycloak JWT (owner {@code sub} extracted server-side)
   * @param request the wire DTO with base64-encoded public key material
   * @return 204 No Content on success
   */
  @PutMapping("/v1/keys")
  ResponseEntity<Void> publish(
      @AuthenticationPrincipal Jwt jwt, @RequestBody PublishKeyBundleRequest request) {
    registerDeviceKeysUseCase.register(
        new RegisterDeviceKeysCommand(jwt.getSubject(), request.toDomain()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Returns the recipient's public prekey bundle for X3DH key agreement. Atomically consumes
   * exactly one one-time prekey from the recipient's pool (if available). Any authenticated caller
   * may fetch any registered sub's bundle — no self-restriction enforced here (the E2EE layer
   * ensures only the correct sender uses the result).
   *
   * <p>The caller's own sub is never used or logged; privacy: root CLAUDE.md §0.6.
   *
   * @param sub the Keycloak subject of the recipient device owner
   * @return 200 with the prekey bundle, or 404 if no device is registered for {@code sub}
   */
  @GetMapping("/v1/keys/{sub}")
  WrappedResponse<PreKeyBundleResponse> fetch(@PathVariable String sub) {
    return WrappedResponse.ok(PreKeyBundleResponse.from(fetchUseCase.fetch(sub)));
  }
}
