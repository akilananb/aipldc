package ai.pdlc.core.domain;

import java.util.List;

/**
 * Release agent → gate 3 (then monitor agent) handoff — {@code docs/agent-playbook.md} §7
 * "Produces". Extends the base {@link Handoff} envelope by composition.
 *
 * @param envelope     base handoff envelope
 * @param releaseId    e.g. {@code R-2026-08-31-04}
 * @param documents    the release pack — one entry per {@link ReleaseDocument}
 * @param rollout      the rollout/rollback plan
 * @param monitorRules rules the monitor agent evaluates after deploy
 */
public record ReleaseHandoff(
        Handoff envelope,
        String releaseId,
        List<ReleaseDocument> documents,
        RolloutPlan rollout,
        List<MonitorRule> monitorRules) {

    public ReleaseHandoff {
        documents = documents == null ? List.of() : List.copyOf(documents);
        monitorRules = monitorRules == null ? List.of() : List.copyOf(monitorRules);
    }

    public ReleaseDocument document(String id) {
        return documents.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such release document: " + id));
    }
}
