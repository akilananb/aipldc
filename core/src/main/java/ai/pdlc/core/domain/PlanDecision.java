package ai.pdlc.core.domain;

import java.util.List;

/**
 * The Plan Agent's response to one {@code planNextStep} reasoning call —
 * {@code ai.pdlc.core.workflow.PlanningLoop} drives the bounded conversation this decision shape
 * powers. Exactly one of the three {@link Action} shapes is valid per decision; a decision that
 * does not match its action's required shape is an invalid transition (consumes a decision,
 * produces feedback — see {@code PlanningLoop}), never a successful plan.
 *
 * @param action    what the Plan Agent wants to do next
 * @param reason    nonblank human-readable rationale, always required
 * @param repo      CONSULT only: the single project repo id this round inspects; {@code null} for
 *                  FINALIZE/BLOCKED
 * @param questions CONSULT only: 1-8 nonblank repository questions
 * @param paths     CONSULT only: repo-relative paths (may be empty) to check for existence
 * @param tasks     FINALIZE only: the proposed task breakdown, subject to
 *                  {@code ai.pdlc.core.plan.PlanAssembler} validation before publication
 */
public record PlanDecision(Action action, String reason, String repo,
                            List<String> questions, List<String> paths, List<PlannedTask> tasks) {

    public enum Action { CONSULT, FINALIZE, BLOCKED }

    public PlanDecision {
        questions = questions == null ? List.of() : List.copyOf(questions);
        paths = paths == null ? List.of() : List.copyOf(paths);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** One proposed task, before {@code PlanAssembler} assigns budgets/waves and validates
     * coverage/scope against the accumulated consultation file catalog.
     *
     * @param id          proposed sequential id, e.g. {@code T1} — re-validated, never trusted
     * @param title       short imperative title
     * @param description markdown brief: approach/functions found in the evidence, what the test asserts
     * @param area        code area — must be one of the story's {@link PoHandoff#areas()}
     * @param repo        id of the project repo this task touches; must be one of the project's repos
     * @param scenario    the spec-delta scenario this task proves
     * @param touches     files the build loop may edit; every path must appear in the consultation catalog
     * @param testPath    the test file that proves {@code scenario}; must appear in the catalog
     * @param blockedBy   ids of earlier tasks that must complete first */
    public record PlannedTask(String id, String title, String description,
                               String area, String repo, String scenario, List<String> touches,
                               String testPath, List<String> blockedBy) {
        public PlannedTask {
            touches = touches == null ? List.of() : List.copyOf(touches);
            blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        }
    }
}
