package ai.pdlc.core.config;

import java.util.List;

/**
 * Admin-editable project metadata — name, Confluence link, reference docs, freeform brief injected
 * into agent prompts as "Project context", and build defaults.
 */
public record ProjectMeta(
        String id,
        String name,
        String confluenceUrl,
        List<DocLink> docs,
        String brief,
        BuildDefaults build) {

    public ProjectMeta {
        docs = docs == null ? List.of() : List.copyOf(docs);
        brief = brief == null ? "" : brief;
        build = build == null ? new BuildDefaults("omp acp") : build;
    }

    public record DocLink(String title, String url) {
    }

    public record BuildDefaults(String acpAgent) {
        public BuildDefaults {
            acpAgent = acpAgent == null || acpAgent.isBlank() ? "omp acp" : acpAgent;
        }
    }
}
