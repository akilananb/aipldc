package ai.pdlc.controlplane.web.dto;

import ai.pdlc.core.domain.Anchor;

import java.util.UUID;

public record CommentDto(
        UUID id,
        String by,
        String role,
        String target,
        String text,
        String intent,
        boolean blocking,
        int version,
        Integer resolvedInVersion,
        String agentReply,
        Anchor anchor,
        boolean drifted) {
}
