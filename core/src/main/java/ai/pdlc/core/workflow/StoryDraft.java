package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.PoHandoff;

import java.util.Map;

/**
 * Result of {@code poDraft}/{@code poRevise} — the PO agent's typed handoff plus the two texts it
 * writes twice from one source (playbook §2 "Purpose"): the ADO story markdown and the OpenSpec
 * change-folder files (path → content), keyed relative to {@code openspec/changes/<slug>/}.
 */
public record StoryDraft(PoHandoff handoff, String storyMarkdown, Map<String, String> specDeltaFiles) {
    public StoryDraft {
        specDeltaFiles = specDeltaFiles == null ? Map.of() : Map.copyOf(specDeltaFiles);
    }
}
