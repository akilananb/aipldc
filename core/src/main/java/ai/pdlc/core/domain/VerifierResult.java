package ai.pdlc.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/**
 * The build worker's verifier output for one task — {@code docs/agent-playbook.md} §5 "Verifier".
 * Deterministic first (test pass/fail); no LLM rubric in the pilot.
 *
 * @param result          {@code green | red}
 * @param scenarioResults scenario name → did its tagged test pass
 * @param scopeOk         the diff stayed within the task's {@code touches} list
 * @param notes           human-readable summary, e.g. failing test names
 */
public record VerifierResult(String result, Map<String, Boolean> scenarioResults, boolean scopeOk, String notes) {

    public VerifierResult {
        scenarioResults = scenarioResults == null ? Map.of() : Map.copyOf(scenarioResults);
    }

    /** Convenience predicate, not a wire field — {@code isXxx()} is a JavaBean getter pattern
     * Jackson would otherwise serialize as an extra {@code green} property no constructor accepts. */
    @JsonIgnore
    public boolean isGreen() {
        return "green".equals(result) && scopeOk;
    }
}
