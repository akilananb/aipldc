package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/** A connection's metadata. {@code secretRef} is a reference ({@code kv://name}); no secret value is ever returned. */
public record ConnectionDto(String id, String scope, String workspaceId, String kind, String authType, String secretRef,
                            String baseUrl, String status, OffsetDateTime expiresAt, OffsetDateTime createdAt,
                            String createdBy, OffsetDateTime updatedAt, String updatedBy, OffsetDateTime revokedAt,
                            String revokedBy, String oauthClientId) {
}
