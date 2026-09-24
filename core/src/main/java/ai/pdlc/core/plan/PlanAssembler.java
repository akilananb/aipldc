package ai.pdlc.core.plan;

import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PlanDecision;
import ai.pdlc.core.domain.PlanHandoff;
import ai.pdlc.core.domain.PlanResult;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.QualityReport;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * The single Java owner of final-plan validation and assembly — the Plan Agent's {@code
 * PlanDecision.FINALIZE} draft tasks plus the accumulated {@link PlanConsultation.Exchange}
 * transcript go in, a coverage/scope/DAG-validated {@link PlanResult} comes out (or an {@link
 * IllegalArgumentException} naming every violation, fed back to the next reasoning decision by
 * {@code ai.pdlc.core.workflow.PlanningLoop}). Ports the former {@code build-worker/src/
 * planValidator.ts} task-shape/coverage checks (now deleted) plus the touches/testPath ->
 * consultation-catalog scope rule that TS validator never had, then reuses {@link PlanWaves#compute}
 * and {@link PlanValidator#violations} exactly as the pre-consultation planner did.
 */
public final class PlanAssembler {

    private static final int TOUCHES_LIMIT = 8;
    private static final Task.TaskBudget DEFAULT_TASK_BUDGET =
            new Task.TaskBudget(6, 120_000L, Duration.ofMinutes(10));

    private PlanAssembler() {
    }

    public static PlanResult assemble(WorkItemRef story, PoHandoff po,
                                       List<PlanDecision.PlannedTask> drafts,
                                       List<PlanConsultation.Exchange> history,
                                       List<RepoConfig> repos) {
        List<String> errors = new ArrayList<>();
        Map<String, RepoConfig> repoById = new LinkedHashMap<>();
        for (RepoConfig r : repos) {
            repoById.put(r.id(), r);
        }

        CatalogResult catalog = buildCatalog(history, errors);

        List<Task> tasks = new ArrayList<>();
        if (drafts == null || drafts.isEmpty()) {
            errors.add("tasks must be a non-empty list");
        } else {
            Set<String> ids = new LinkedHashSet<>();
            Map<String, Integer> scenarioCounts = new LinkedHashMap<>();
            for (int i = 0; i < drafts.size(); i++) {
                tasks.add(validateAndBuildTask(i, drafts.get(i), po, repoById, catalog.byKey, ids, scenarioCounts, errors));
            }
            for (String scenario : po.scenarios()) {
                int count = scenarioCounts.getOrDefault(scenario, 0);
                if (count == 0) {
                    errors.add("scenario \"" + scenario + "\" has no task");
                } else if (count > 1) {
                    errors.add("scenario \"" + scenario + "\" is covered by " + count + " tasks (must be exactly 1)");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("plan rejected: " + String.join("; ", errors));
        }

        List<List<String>> waves;
        try {
            waves = PlanWaves.compute(tasks);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("plan rejected: " + e.getMessage());
        }

        List<String> inputsRead = new ArrayList<>();
        inputsRead.add("po:" + po.change());
        for (String baseCommit : catalog.baseCommitByRepo.values()) {
            if (baseCommit != null && !baseCommit.isBlank()) {
                inputsRead.add("repo:" + baseCommit);
            }
        }
        Handoff envelope = new Handoff("plan-agent", "build-worker", story.boardId(),
                CanonicalState.PLANNED, inputsRead, 0.85, List.of(), List.of());
        PlanHandoff plan = new PlanHandoff(envelope, tasks, waves);

        List<String> violations = PlanValidator.violations(plan, po.scenarios());
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("plan rejected: " + String.join("; ", violations));
        }

        Set<String> usedKeys = new LinkedHashSet<>();
        for (Task t : tasks) {
            for (String path : t.touches()) {
                usedKeys.add(t.repo() + ":" + path);
            }
            usedKeys.add(t.repo() + ":" + t.testPath());
        }
        Set<String> newFiles = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> e : catalog.byKey.entrySet()) {
            if (Boolean.FALSE.equals(e.getValue()) && usedKeys.contains(e.getKey())) {
                int sep = e.getKey().indexOf(':');
                newFiles.add(e.getKey().substring(sep + 1));
            }
        }

        Map<String, QualityReport> checksByTaskId = new LinkedHashMap<>();
        for (Task t : tasks) {
            checksByTaskId.put(t.id(), PlanChecks.report(plan, t, newFiles));
        }
        return new PlanResult(plan, checksByTaskId);
    }

    private record CatalogResult(Map<String, Boolean> byKey, Map<String, String> baseCommitByRepo) {
    }

    /** Merges every exchange's file evidence into one {@code repoId:path -> exists} catalog per
     * repo, rejecting conflicting existence flags or a report whose {@code baseCommit} disagrees
     * with an earlier one for the same repo in the same transcript. */
    private static CatalogResult buildCatalog(List<PlanConsultation.Exchange> history, List<String> errors) {
        Map<String, Boolean> catalog = new LinkedHashMap<>();
        Map<String, String> baseCommitByRepo = new LinkedHashMap<>();
        if (history != null) {
            for (PlanConsultation.Exchange exchange : history) {
                PlanConsultation.Report report = exchange.report();
                if (report == null) {
                    continue;
                }
                String repoId = exchange.repoId();
                String existingBase = baseCommitByRepo.get(repoId);
                if (existingBase == null) {
                    baseCommitByRepo.put(repoId, report.baseCommit());
                } else if (!existingBase.equals(report.baseCommit())) {
                    errors.add("consultation reports for repo \"" + repoId + "\" disagree on repository snapshot: "
                            + existingBase + " vs " + report.baseCommit());
                }
                for (PlanConsultation.FileEvidence fe : report.files()) {
                    if (!isValidRepoPath(fe.path())) {
                        continue;
                    }
                    String key = repoId + ":" + fe.path();
                    Boolean existing = catalog.get(key);
                    if (existing != null && !existing.equals(fe.exists())) {
                        errors.add("conflicting existence evidence for " + key);
                    } else {
                        catalog.put(key, fe.exists());
                    }
                }
            }
        }
        return new CatalogResult(catalog, baseCommitByRepo);
    }

    private static Task validateAndBuildTask(int index, PlanDecision.PlannedTask t, PoHandoff po,
                                              Map<String, RepoConfig> repoById, Map<String, Boolean> catalog,
                                              Set<String> ids, Map<String, Integer> scenarioCounts, List<String> errors) {
        String expectedId = "T" + (index + 1);
        String id = t.id() == null ? expectedId : t.id();
        if (!id.equals(expectedId)) {
            errors.add("task " + (index + 1) + ": id must be " + expectedId + " in order");
        }
        ids.add(id);

        String title = t.title() == null ? "" : t.title().trim();
        if (title.isEmpty() || title.length() > 70) {
            errors.add(id + ": title blank or over 70 characters");
        }
        if (title.contains("\"") || title.contains("'")) {
            errors.add(id + ": title contains a quotation mark");
        }
        if (title.matches("(?i)^(implement|task)\\b.*")) {
            errors.add(id + ": title starts with \"implement\" or \"task\"");
        }

        String description = t.description() == null ? "" : t.description().trim();
        if (description.isEmpty()) {
            errors.add(id + ": description must be non-blank");
        }

        String repoId = t.repo() == null ? "" : t.repo();
        RepoConfig repo = repoById.get(repoId);
        if (repo == null) {
            errors.add(id + ": repo \"" + repoId + "\" is not a project repo");
        }

        String area = t.area() == null ? "" : t.area();
        if (!po.areas().contains(area)) {
            errors.add(id + ": area \"" + area + "\" is not one of the story's areas");
        } else if (repo != null && !repo.areas().isEmpty() && !repo.areas().contains(area)) {
            errors.add(id + ": area \"" + area + "\" is not served by repo \"" + repoId + "\"");
        }

        String scenario = t.scenario() == null ? "" : t.scenario();
        if (!po.scenarios().contains(scenario)) {
            errors.add(id + ": scenario \"" + scenario + "\" is not in the story");
        }
        scenarioCounts.merge(scenario, 1, Integer::sum);

        List<String> touches = t.touches();
        boolean touchesShapeOk = !touches.isEmpty() && touches.size() <= TOUCHES_LIMIT;
        for (String path : touches) {
            if (!isValidRepoPath(path)) {
                touchesShapeOk = false;
                errors.add(id + ": touches path \"" + path + "\" is not a valid repo-relative path");
            } else if (!catalog.containsKey(repoId + ":" + path)) {
                errors.add(id + ": touches path \"" + path + "\" has no consultation catalog entry");
            }
        }
        if (!touchesShapeOk) {
            errors.add(id + ": touches must list 1-" + TOUCHES_LIMIT + " repo-relative paths");
        }

        String testPath = t.testPath() == null ? "" : t.testPath();
        if (testPath.isEmpty()) {
            errors.add(id + ": testPath required");
        } else if (!isValidRepoPath(testPath)) {
            errors.add(id + ": testPath is not a valid repo-relative path");
        } else if (!catalog.containsKey(repoId + ":" + testPath)) {
            errors.add(id + ": testPath \"" + testPath + "\" has no consultation catalog entry");
        }

        for (String blockerId : t.blockedBy()) {
            if (!ids.contains(blockerId) || blockerId.equals(id)) {
                errors.add(id + ": blockedBy references unknown or later task " + blockerId);
            }
        }

        return new Task(id, title, description, area, repoId, scenario, touches, testPath, DEFAULT_TASK_BUDGET, t.blockedBy());
    }

    /** Nonblank slash-separated repository-relative path — rejects absolute/drive-prefixed paths,
     * backslashes, NUL, empty/dot/parent segments, and {@code .git}/{@code .pdlc} as the first
     * segment. These paths become builder permissions; the same rule is enforced in TypeScript by
     * {@code build-worker/src/planConsultationValidator.ts}. */
    public static boolean isValidRepoPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return false;
        }
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return false;
        }
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        String first = segments[0];
        return !first.equals(".git") && !first.equals(".pdlc");
    }
}
