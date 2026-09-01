package ai.pdlc.core.review;

import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.ReleaseDocument;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.RolloutPlan;

/**
 * Renders {@code manifest.yaml} for a release pack — tech-stack §3.4 "each document ... a
 * {@code manifest.yaml} entry". Pure formatting, no I/O; mirrors {@link GrillMdSerializer}'s shape.
 */
public final class ReleaseManifestSerializer {

    private ReleaseManifestSerializer() {
    }

    public static String render(ReleaseHandoff release) {
        StringBuilder sb = new StringBuilder();
        sb.append("release_id: ").append(release.releaseId()).append('\n');
        sb.append("documents:\n");
        for (ReleaseDocument doc : release.documents()) {
            sb.append("  - id: ").append(doc.id()).append('\n');
            sb.append("    title: ").append(doc.title()).append('\n');
            sb.append("    checker_role: ").append(doc.checkerRole()).append('\n');
            sb.append("    status: unsigned\n");
        }
        RolloutPlan rollout = release.rollout();
        sb.append("rollout:\n");
        sb.append("  canary_pct: ").append(rollout.canaryPct()).append('\n');
        sb.append("  soak_minutes: ").append(rollout.soakMinutes()).append('\n');
        sb.append("  flag: ").append(rollout.flag()).append('\n');
        sb.append("  rollback: ").append(yamlScalar(rollout.rollback())).append('\n');
        sb.append("monitor_rules:\n");
        for (MonitorRule rule : release.monitorRules()) {
            sb.append("  - id: ").append(rule.id()).append('\n');
            sb.append("    signal: ").append(rule.signal()).append('\n');
            sb.append("    threshold: ").append(yamlScalar(rule.threshold())).append('\n');
            sb.append("    action: ").append(rule.action()).append('\n');
            sb.append("    owner: ").append(rule.owner()).append('\n');
        }
        return sb.toString();
    }

    private static String yamlScalar(String text) {
        if (text == null) {
            return "null";
        }
        return text.contains(":") || text.contains("#") ? "\"" + text.replace("\"", "\\\"") + "\"" : text;
    }
}
