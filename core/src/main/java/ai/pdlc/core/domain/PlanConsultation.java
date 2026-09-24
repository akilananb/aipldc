package ai.pdlc.core.domain;

import java.util.List;

/**
 * One repository-consultation round the Plan Agent requests from the build-worker's ACP-driven
 * consultant (build-order phase 3 — see {@code ai.pdlc.core.workflow.PlanningLoop}). Unlike the
 * old one-shot deterministic planner, the Plan Agent's task breakdown is produced only after zero
 * or more bounded rounds of asking the repository (via omp) evidence-backed questions.
 *
 * @param round      1-based across successful consultation exchanges — never derived from an
 *                   activity retry {@code attempt} or an assisted omp launch count
 * @param repoId     id of the project repo this round inspects
 * @param questions  1-8 nonblank repository questions the consultant must answer with evidence
 * @param paths      repo-relative paths (may be empty) whose existence the consultant must check
 * @param baseCommit the exact commit SHA every consultant round for this repo must pin its
 *                   worktree to; blank only for the first consultation of this repo, which
 *                   resolves the default branch itself
 * @param history    every prior successful exchange (any repo), oldest first — immutable transcript
 */
public record PlanConsultation(
        int round, String repoId, List<String> questions, List<String> paths,
        String baseCommit, List<Exchange> history) {

    public PlanConsultation {
        questions = questions == null ? List.of() : List.copyOf(questions);
        paths = paths == null ? List.of() : List.copyOf(paths);
        baseCommit = baseCommit == null ? "" : baseCommit;
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** The consultant's evidence for one round: findings plus a file-existence catalog, all
     * pinned to the exact commit the consultant actually checked out.
     *
     * @param baseCommit      the worker-observed SHA the consultant's worktree was pinned to
     * @param findingsMarkdown answers with repository evidence and unresolved uncertainties
     * @param files            every path checked this round (requested and consultant-suggested) */
    public record Report(String baseCommit, String findingsMarkdown, List<FileEvidence> files) {
        public Report {
            baseCommit = baseCommit == null ? "" : baseCommit;
            findingsMarkdown = findingsMarkdown == null ? "" : findingsMarkdown;
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** One catalog entry: {@code exists=false} is an explicitly checked new-file path, not an
     * error — filesystem-observed only, never an LLM-asserted flag. */
    public record FileEvidence(String path, boolean exists) {
    }

    /** One completed round: the questions/paths asked plus the consultant's report. */
    public record Exchange(int round, String repoId, List<String> questions, Report report) {
        public Exchange {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }
}
