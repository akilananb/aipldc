package ai.pdlc.core.config;

import java.util.List;

/** {@code pdlc.yaml} §4 {@code repos[]} entry — one repo a project's board items may touch. */
public record RepoConfig(
        String id,
        String provider,
        String url,
        String defaultBranch,
        String specDir,
        List<String> areas,
        boolean primary) {

    public RepoConfig {
        areas = areas == null ? List.of() : List.copyOf(areas);
        if (id == null || id.isBlank()) {
            throw new PdlcConfigException("repo.id is required");
        }
    }
}
