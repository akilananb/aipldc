package ai.pdlc.controlplane.web.dto;

import ai.pdlc.controlplane.platform.Capability;

import java.time.OffsetDateTime;
import java.util.Set;

/** A workspace as seen by the caller: {@code capabilities} are the caller's own grants in it. */
public record WorkspaceDto(String id, String name, OffsetDateTime createdAt, String createdBy,
                           Set<Capability> capabilities) {
}
