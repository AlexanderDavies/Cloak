package com.cloak.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed auth config bound from {@code cloak.auth.*}: the OIDC issuer and required audience. */
@ConfigurationProperties(prefix = "cloak.auth")
public record AuthProperties(String issuerUri, String audience) {}
