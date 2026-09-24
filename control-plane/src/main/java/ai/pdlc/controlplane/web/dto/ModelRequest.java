package ai.pdlc.controlplane.web.dto;

/** Create ({@code id} required) or update a catalog model; {@code enabled} defaults to true on create and is unchanged when omitted on update. */
public record ModelRequest(String id, String connectionId, String providerModel, String displayName, Boolean enabled) {
}
