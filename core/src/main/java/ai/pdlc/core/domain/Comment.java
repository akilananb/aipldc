package ai.pdlc.core.domain;

/**
 * A review comment, matching {@code docs/agent-playbook.md} lines 548-557 "comment contract" plus
 * {@code role} and {@code version} (the artifact version the comment was posted against), which the
 * data model (tech-stack §5 {@code comments} table) and the maker-checker resolution loop both need.
 *
 * @param id       comment id
 * @param by       author identity (OIDC sub, or a bot identity for agent replies)
 * @param role     author's board role, e.g. {@code PO|SquadLead|FSDeveloper|QA}
 * @param stage    {@code grill|story|plan|build|review|release-pack:<doc-id>}
 * @param target   {@code line:N | scenario:<name> | task:T2 | file:path:line | doc:<id>#section}
 * @param text     comment body
 * @param intent   {@code change|question|note}
 * @param blocking blocking comments must be resolved before any approval
 * @param version  artifact version this comment was posted against
 */
public record Comment(
        String id,
        String by,
        String role,
        String stage,
        String target,
        String text,
        Intent intent,
        boolean blocking,
        int version) {

    public enum Intent {
        CHANGE, QUESTION, NOTE;

        public static Intent fromWire(String wire) {
            return Intent.valueOf(wire.toUpperCase());
        }

        public String wireValue() {
            return name().toLowerCase();
        }
    }
}
