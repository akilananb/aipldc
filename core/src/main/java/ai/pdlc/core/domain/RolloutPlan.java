package ai.pdlc.core.domain;

/**
 * The release agent's rollout/rollback plan — {@code docs/agent-playbook.md} §7 handoff
 * {@code rollout} block.
 *
 * @param canaryPct  percentage of traffic/users on the canary before promote
 * @param soakMinutes minutes to watch the canary before promote
 * @param flag       feature flag name gating the change
 * @param rollback   the rollback command/action, e.g. {@code "flag off + deploy prev"}
 */
public record RolloutPlan(int canaryPct, int soakMinutes, String flag, String rollback) {
}
