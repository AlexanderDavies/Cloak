package com.cloak.server.adapter.input.rest.keys;

import com.cloak.server.usecase.RegisterDeviceKeysCommand;
import com.cloak.server.usecase.RegisterDeviceKeysUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the calling device's public prekey bundle (Slice 1). Owner is derived from the JWT. */
@RestController
public class DeviceKeyController {
  private final RegisterDeviceKeysUseCase useCase;

  DeviceKeyController(RegisterDeviceKeysUseCase useCase) {
    this.useCase = useCase;
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
    useCase.register(new RegisterDeviceKeysCommand(jwt.getSubject(), request.toDomain()));
    return ResponseEntity.noContent().build();
  }
}
