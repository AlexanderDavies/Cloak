@preconcurrency import AppAuth
import Foundation
import UIKit

/// OIDC-PKCE auth boundary (guide §10). `@MainActor` because AppAuth presents UI on the main thread.
@MainActor
protocol AuthService {
    /// Runs OIDC-PKCE against Keycloak; returns an access token.
    func login(presenting: UIViewController) async throws -> String
}

/// AppAuth-based OIDC-PKCE login against the Keycloak `cloak-ios` client.
@MainActor
final class KeycloakAuthService: AuthService {
    private let issuer: URL
    private let clientId = "cloak-ios"
    private let redirect = URL(string: "com.cloak.app://oauth-callback")
    private var session: OIDExternalUserAgentSession?

    init(issuer: URL) { self.issuer = issuer }

    enum AuthError: Error { case misconfigured, noToken }

    func login(presenting: UIViewController) async throws -> String {
        guard let redirect else { throw AuthError.misconfigured }

        let config: OIDServiceConfiguration = try await withCheckedThrowingContinuation { cont in
            OIDAuthorizationService.discoverConfiguration(forIssuer: issuer) { cfg, err in
                if let cfg {
                    cont.resume(returning: cfg)
                } else {
                    cont.resume(throwing: err ?? AuthError.misconfigured)
                }
            }
        }

        let request = OIDAuthorizationRequest(
            configuration: config, clientId: clientId, clientSecret: nil,
            scopes: [OIDScopeOpenID, OIDScopeProfile], redirectURL: redirect,
            responseType: OIDResponseTypeCode, additionalParameters: nil)

        return try await withCheckedThrowingContinuation { cont in
            session = OIDAuthState.authState(byPresenting: request, presenting: presenting) { state, err in
                if let token = state?.lastTokenResponse?.accessToken {
                    cont.resume(returning: token)
                } else {
                    cont.resume(throwing: err ?? AuthError.noToken)
                }
            }
        }
    }
}
