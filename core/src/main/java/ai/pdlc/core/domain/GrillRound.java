package ai.pdlc.core.domain;

/**
 * One adaptive-grill reasoning round's result (ADAPTIVE_GRILL_PLAN.md step 3). {@code handoff} is
 * the complete accumulated question history, including every preserved answer, author, and park;
 * {@code summary} is required and nonblank exactly when {@code handoff} has no new open questions
 * — the candidate shared-understanding summary, not approval.
 */
public record GrillRound(GrillHandoff handoff, String summary) {
}
