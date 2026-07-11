package com.cloak.server.adapter.input.rest.users;

/**
 * Wire DTO for {@code GET /v1/users/lookup}. Carries the resolved Keycloak subject and a device
 * number (always {@code 1} in Slice 2 — multi-device support is deferred). Sub is included because
 * the caller needs it to address the recipient in X3DH; no other identity data is exposed.
 */
public record UserLookupResponse(String sub, int deviceId) {}
