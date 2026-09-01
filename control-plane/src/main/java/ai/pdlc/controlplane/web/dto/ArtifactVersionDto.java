package ai.pdlc.controlplane.web.dto;

import java.util.List;

public record ArtifactVersionDto(int version, String contentHash, String storyMarkdown, List<CommentDto> comments) {
}
