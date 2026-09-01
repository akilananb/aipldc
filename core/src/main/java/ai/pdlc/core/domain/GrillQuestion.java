package ai.pdlc.core.domain;

/**
 * One grill question — {@code docs/agent-playbook.md} §1. Six fixed {@link Category} values; each
 * question must cite {@code evidence} or be marked {@code assumption-check} (an evidence value the
 * grill agent uses verbatim when no concrete evidence exists).
 */
public record GrillQuestion(
        String id,
        Category category,
        String question,
        String evidence,
        Status status,
        String answer,
        String answeredBy) {

    public static final String ASSUMPTION_CHECK = "assumption-check";

    public enum Category {
        SCOPE, USERS, ACCEPTANCE, RISK, DEPENDENCY, NFR;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public enum Status {
        OPEN, ANSWERED, PARKED;

        public String wireValue() {
            return name().toLowerCase();
        }
    }

    public GrillQuestion withAnswer(String answer, String answeredBy) {
        return new GrillQuestion(id, category, question, evidence, Status.ANSWERED, answer, answeredBy);
    }

    public GrillQuestion parked() {
        return new GrillQuestion(id, category, question, evidence, Status.PARKED, answer, answeredBy);
    }
}
