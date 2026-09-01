package ai.pdlc.core.domain;

/**
 * One monitor rule derived from NFRs/risk scenarios — {@code docs/agent-playbook.md} §7 handoff
 * {@code monitor_rules} entries, evaluated by the monitor agent (§8) via {@link
 * ai.pdlc.core.port.MetricsPort}.
 *
 * @param id        stable rule id, e.g. {@code export-error-rate}
 * @param signal    the metric series name {@link ai.pdlc.core.port.MetricsPort#query} reads
 * @param threshold human-readable threshold, e.g. {@code "> 2% over 15m"}
 * @param action    {@code file-card | file-card+notify-squad-lead}
 * @param owner     board role notified/assigned on a trip
 */
public record MonitorRule(String id, String signal, String threshold, String action, String owner) {
}
