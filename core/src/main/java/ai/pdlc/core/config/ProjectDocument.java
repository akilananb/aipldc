package ai.pdlc.core.config;

import java.util.List;
import java.util.Map;

/**
 * The Admin-editable, DB-persisted half of a project: {@code projects.config_json}. Combined with
 * the deployment's {@link AgentsConfig}/{@link NotifyConfig} (from {@code pdlc.yaml}, not editable
 * per-project) this forms a full {@link Profile}. Jackson-friendly: records, {@code List}/{@code Map}
 * only, no custom types.
 */
public record ProjectDocument(
        ProjectMeta project,
        BoardConfig board,
        List<RepoConfig> repos,
        Map<String, GateConfig> gates) {
}
