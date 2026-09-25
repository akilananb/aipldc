package ai.pdlc.controlplane.web.dto;

import java.util.List;

/** Publication check result for a tool draft; {@code contentHash} is what publishing would pin. */
public record ToolValidationDto(boolean valid, List<String> errors, String contentHash, int draftRevision) {
}
