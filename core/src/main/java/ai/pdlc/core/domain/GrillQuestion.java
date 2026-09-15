package ai.pdlc.core.domain;

/**
 * One grill question — {@code docs/agent-playbook.md} §1. Six fixed grill {@link Category} values
 * plus {@code BUILD} (never grill-generated; only the build loop's own human-input questions use
 * it); each question must cite {@code evidence} or be marked {@code assumption-check} (an evidence
 * value the grill agent uses verbatim when no concrete evidence exists).
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

    /** Id prefix for follow-up questions the PO agent asks — e.g. {@code po1}, {@code po2}. */
    public static final String PO_ID_PREFIX = "po";

    /** Id prefix for questions the build loop asks a human after a task escalation — e.g.
     * {@code h1}, {@code h2} (playbook: build agent "Stop conditions" human-input path). */
    public static final String HUMAN_INPUT_ID_PREFIX = "h";

    public enum Category {
        SCOPE, USERS, ACCEPTANCE, RISK, DEPENDENCY, NFR, BUILD;

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

    /** True when this question was raised by the PO agent as a drafting follow-up (id {@code po*}). */
    public boolean askedByPoAgent() {
        return id.startsWith(PO_ID_PREFIX);
    }

    /** True when this question was raised by the build loop after a task escalation (id {@code h*}). */
    public boolean askedByBuildLoop() {
        return id.startsWith(HUMAN_INPUT_ID_PREFIX);
    }
}
