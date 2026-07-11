package com.cloak.server.domain.identity;

/**
 * Domain read model for a located Keycloak user. Carries only the stable Keycloak subject ({@code
 * sub}) — the minimal information needed for X3DH key lookup. No email, username, or profile data
 * crosses this boundary (privacy: root CLAUDE.md §0.6).
 */
public record DirectoryUser(String sub) {}
