package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.VerifierResult;

import java.util.List;

/**
 * Result of {@link BuildActivities#runTask} — {@code docs/agent-playbook.md} §4 build agent
 * handoff's {@code proves/verifier/iterations/tokens/touched/trace/escalation} fields.
 *
 * @param taskId       the {@link ai.pdlc.core.domain.Task} id this result is for
 * @param commitSha    HEAD of the shared story branch after this task's commit(s)
 * @param verifier     the verifier's result for this task's scenario
 * @param iterations   build-loop iterations used
 * @param tokens       tokens spent (0 if the model gateway does not report usage)
 * @param touchedFiles files the build loop actually changed
 * @param traceSummary short human-readable summary of what happened, for review.md/tasks.md
 * @param escalation   non-null when the loop hit a stop condition other than "green" (playbook
 *                     §4 "Stop conditions" — budget exhausted, stuck, forbidden action)
 */
public record BuildResult(
        String taskId,
        String commitSha,
        VerifierResult verifier,
        int iterations,
        long tokens,
        List<String> touchedFiles,
        String traceSummary,
        String escalation) {

    public BuildResult {
        touchedFiles = touchedFiles == null ? List.of() : List.copyOf(touchedFiles);
    }
}
