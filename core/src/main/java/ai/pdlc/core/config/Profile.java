package ai.pdlc.core.config;

import java.util.List;
import java.util.Map;

/** One project (board + repos + gates + agents/notify) — tech-stack §4. */
public record Profile(
        String name,
        ProjectMeta project,
        BoardConfig board,
        List<RepoConfig> repos,
        NotifyConfig notifyConfig,
        AgentsConfig agents,
        Map<String, GateConfig> gates) {

    public Profile {
        if (repos == null || repos.isEmpty()) {
            throw new PdlcConfigException("profile " + name + ": at least one repo is required");
        }
        repos = List.copyOf(repos);
        Map<String, RepoConfig> byId = new java.util.LinkedHashMap<>();
        RepoConfig primary = null;
        java.util.Set<String> seenAreas = new java.util.HashSet<>();
        for (RepoConfig r : repos) {
            if (byId.put(r.id(), r) != null) {
                throw new PdlcConfigException("profile " + name + ": duplicate repo id \"" + r.id() + "\"");
            }
            if (r.primary()) {
                if (primary != null) {
                    throw new PdlcConfigException("profile " + name + ": exactly one repo must be primary");
                }
                primary = r;
            }
            for (String area : r.areas()) {
                if (!seenAreas.add(area)) {
                    throw new PdlcConfigException("profile " + name + ": area \"" + area + "\" is served by more than one repo");
                }
            }
        }
        if (primary == null) {
            throw new PdlcConfigException("profile " + name + ": exactly one repo must be primary");
        }
    }

    public GateConfig gate(String id) {
        GateConfig g = gates.get(id);
        if (g == null) {
            throw new PdlcConfigException("Missing profile key: gates." + id);
        }
        return g;
    }

    /** The project's primary repo — target for the story's spec/tasks.md/review.md. */
    public RepoConfig repo() {
        return repos.stream().filter(RepoConfig::primary).findFirst()
                .orElseThrow(() -> new PdlcConfigException("profile " + name + ": no primary repo"));
    }

    public RepoConfig repo(String repoId) {
        return repos.stream().filter(r -> r.id().equals(repoId)).findFirst()
                .orElseThrow(() -> new PdlcConfigException("Missing repo: " + repoId));
    }

    /** The repo whose {@code areas} declares {@code area}, else the primary repo. */
    public RepoConfig repoForArea(String area) {
        return repos.stream().filter(r -> r.areas().contains(area)).findFirst().orElseGet(this::repo);
    }
}
