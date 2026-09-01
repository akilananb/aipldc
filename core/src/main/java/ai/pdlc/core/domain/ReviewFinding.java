package ai.pdlc.core.domain;

/**
 * One review agent finding — {@code docs/agent-playbook.md}'s review agent (build-order phase 3).
 *
 * @param severity blocker findings must be resolved before gate 2; should/nit are advisory
 * @param category short tag, e.g. {@code scope|test-quality|scenario-coverage}
 * @param message  human-readable finding text
 * @param file     file the finding is about, or {@code null}
 */
public record ReviewFinding(Severity severity, String category, String message, String file) {

    public enum Severity {
        BLOCKER, SHOULD, NIT;

        public String wireValue() {
            return name().toLowerCase();
        }
    }
}
