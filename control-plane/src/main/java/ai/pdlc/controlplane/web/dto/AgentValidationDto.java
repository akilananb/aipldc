package ai.pdlc.controlplane.web.dto;

import java.util.List;

/** Publication check result for the current draft; {@code contentHash} is what publishing would pin. */
public record AgentValidationDto(boolean valid, List<String> errors, String contentHash, int draftRevision) {
}
