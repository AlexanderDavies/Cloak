package com.cloak.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed config for the Keycloak Admin REST API client, bound from {@code cloak.keycloak-admin.*}.
 * Used exclusively by {@code KeycloakUserDirectoryAdapter} to obtain an admin token (client
 * credentials grant) and search users. The secret is a dev-only value — never a real credential.
 */
@ConfigurationProperties(prefix = "cloak.keycloak-admin")
public record KeycloakAdminProperties(
    String tokenUri, String usersUri, String clientId, String clientSecret) {}
