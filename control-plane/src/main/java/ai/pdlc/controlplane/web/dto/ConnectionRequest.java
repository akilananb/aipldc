package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/**
 * Create ({@code id}, {@code kind}, {@code authType} required) or rotate ({@code secretRef},
 * {@code baseUrl}, {@code expiresAt}, {@code oauthClientId}) a connection. {@code oauthClientId} is
 * required for, and only allowed with, {@code OAUTH_CLIENT_CREDENTIALS} (then {@code secretRef}
 * references the client secret).
 */
public record ConnectionRequest(String id, String kind, String authType, String secretRef, String baseUrl,
                                OffsetDateTime expiresAt, String oauthClientId) {

    public ConnectionRequest(String id, String kind, String authType, String secretRef, String baseUrl, OffsetDateTime expiresAt) {
        this(id, kind, authType, secretRef, baseUrl, expiresAt, null);
    }
}
