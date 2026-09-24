package ai.pdlc.controlplane.web.dto;

import java.time.OffsetDateTime;

/** Create ({@code id}, {@code kind}, {@code authType} required) or rotate ({@code secretRef}, {@code baseUrl}, {@code expiresAt}) a connection. */
public record ConnectionRequest(String id, String kind, String authType, String secretRef, String baseUrl,
                                OffsetDateTime expiresAt) {
}
