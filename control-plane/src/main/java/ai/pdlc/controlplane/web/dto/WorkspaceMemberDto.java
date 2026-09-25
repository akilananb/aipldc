package ai.pdlc.controlplane.web.dto;

import ai.pdlc.controlplane.platform.Capability;

import java.util.Set;

/** One member's capabilities; as a request body, {@code userId} is taken from the path. */
public record WorkspaceMemberDto(String userId, Set<Capability> capabilities) {
}
