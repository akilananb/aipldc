package ai.pdlc.core.config;

import java.util.Map;

/** One {@code pdlc.yaml} profile — tech-stack §4. */
public record Profile(
        String name,
        BoardConfig board,
        RepoConfig repo,
        NotifyConfig notifyConfig,
        AgentsConfig agents,
        Map<String, GateConfig> gates) {

    public GateConfig gate(String id) {
        GateConfig g = gates.get(id);
        if (g == null) {
            throw new PdlcConfigException("Missing profile key: gates." + id);
        }
        return g;
    }
}
