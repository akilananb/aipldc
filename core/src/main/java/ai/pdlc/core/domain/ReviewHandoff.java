package ai.pdlc.core.domain;

import java.util.List;

/**
 * Review agent → gate 2 handoff — {@code docs/agent-playbook.md}'s review agent (build-order
 * phase 3). Extends the base {@link Handoff} envelope by composition.
 *
 * @param envelope     base handoff envelope
 * @param traceability one row per proven scenario: scenario → test → code
 * @param findings     review findings; blocker findings must be resolved before gate 2
 */
public record ReviewHandoff(Handoff envelope, List<TraceabilityRow> traceability, List<ReviewFinding> findings) {

    public ReviewHandoff {
        traceability = traceability == null ? List.of() : List.copyOf(traceability);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public boolean hasBlockers() {
        return findings.stream().anyMatch(f -> f.severity() == ReviewFinding.Severity.BLOCKER);
    }

    public record TraceabilityRow(String scenario, String testRef, String codeRef) {
    }
}
