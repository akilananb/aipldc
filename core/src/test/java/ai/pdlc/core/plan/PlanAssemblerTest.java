package ai.pdlc.core.plan;

import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PlanDecision;
import ai.pdlc.core.domain.PlanResult;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItemRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PlanAssembler} is the single Java owner of final-plan validation and assembly — these
 * cases replace the deleted {@code build-worker/src/planValidator.ts} test suite (task-shape
 * checks: title/quote/prefix, touches count, blockedBy ordering, coverage) plus the new
 * consultation-catalog scope rule TS never had.
 */
class PlanAssemblerTest {

    private static final WorkItemRef STORY = new WorkItemRef("local", "4413");

    private static final List<RepoConfig> REPOS = List.of(
            new RepoConfig("main", "local-git", "url", "main", "openspec", List.of(), true));

    private static PoHandoff po(List<String> scenarios, List<String> areas) {
        Handoff envelope = new Handoff("po-agent", "plan-agent", STORY.boardId(),
                CanonicalState.APPROVED, List.of(), 0.9, List.of(), List.of());
        return new PoHandoff(envelope, STORY.boardId(), "openspec/changes/export", scenarios,
                Map.of(), areas, Map.of(), List.of(), Map.of());
    }

    private static PlanDecision.PlannedTask task(String id, String scenario, String area, List<String> touches,
                                                  String testPath, List<String> blockedBy) {
        return task(id, scenario, area, "main", touches, testPath, blockedBy);
    }

    private static PlanDecision.PlannedTask task(String id, String scenario, String area, String repo,
                                                  List<String> touches, String testPath, List<String> blockedBy) {
        return new PlanDecision.PlannedTask(id, "Title for " + scenario, "Implements " + scenario + ".",
                area, repo, scenario, touches, testPath, blockedBy);
    }

    private static PlanConsultation.Exchange exchange(int round, String baseCommit, Map<String, Boolean> files) {
        return exchange(round, "main", baseCommit, files);
    }

    private static PlanConsultation.Exchange exchange(int round, String repoId, String baseCommit, Map<String, Boolean> files) {
        List<PlanConsultation.FileEvidence> evidence = new ArrayList<>();
        files.forEach((path, exists) -> evidence.add(new PlanConsultation.FileEvidence(path, exists)));
        return new PlanConsultation.Exchange(round, repoId, List.of("q"), new PlanConsultation.Report(baseCommit, "findings", evidence));
    }

    @Test
    void assemblesAValidSinglePlanWithCatalogBackedTouchesAndTestPath() {
        PoHandoff po = po(List.of("export-csv"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(
                exchange(1, "a".repeat(40), Map.of("src/export.js", true, "test/export.test.js", false)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "export-csv", "orders", List.of("src/export.js"), "test/export.test.js", List.of()));

        PlanResult result = PlanAssembler.assemble(STORY, po, drafts, history, REPOS);

        assertThat(result.plan().tasks()).hasSize(1);
        assertThat(result.plan().waves()).containsExactly(List.of("T1"));
        assertThat(result.plan().envelope().inputsRead()).contains("po:openspec/changes/export", "repo:" + "a".repeat(40));
        // A test file the consultant explicitly checked and found missing is accepted, shown as new.
        assertThat(result.checksByTaskId().get("T1").reportMd())
                .contains("test/export.test.js (new file)")
                .contains("src/export.js (exists)");
    }

    @Test
    void rejectsAnUncoveredScenario() {
        PoHandoff po = po(List.of("a", "b"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(task("T1", "a", "orders", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenario \"b\" has no task");
    }

    @Test
    void rejectsADuplicateScenario() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", List.of("src/x.js"), "test/x.test.js", List.of()),
                task("T2", "a", "orders", List.of("src/x.js"), "test/x.test.js", List.of("T1")));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is covered by 2 tasks (must be exactly 1)");
    }

    @Test
    void rejectsABlockerReferencingAnUnknownOrLaterTask() {
        PoHandoff po = po(List.of("a", "b"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", List.of("src/x.js"), "test/x.test.js", List.of("T2")),
                task("T2", "b", "orders", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockedBy references unknown or later task T2");
    }

    @Test
    void rejectsATouchesPathWithNoConsultationCatalogEntry() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), Map.of("src/x.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", List.of("src/never-checked.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("touches path \"src/never-checked.js\" has no consultation catalog entry");
    }

    @Test
    void rejectsConflictingSnapshotEvidenceAcrossTheTranscript() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(
                exchange(1, "a".repeat(40), Map.of("src/x.js", true)),
                exchange(2, "b".repeat(40), Map.of("test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consultation reports for repo \"main\" disagree on repository snapshot");
    }

    @Test
    void rejectsConflictingExistenceEvidenceForTheSamePath() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(
                exchange(1, "a".repeat(40), Map.of("src/x.js", true)),
                exchange(2, "a".repeat(40), Map.of("src/x.js", false, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting existence evidence for main:src/x.js");
    }

    @Test
    void rejectsATitleWithAQuotationMarkOrAnImplementTaskPrefix() {
        PoHandoff po = po(List.of("a", "b"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        PlanDecision.PlannedTask quoted = new PlanDecision.PlannedTask("T1", "\"a\" rate limit", "brief", "orders",
                "main", "a", List.of("src/x.js"), "test/x.test.js", List.of());
        PlanDecision.PlannedTask prefixed = new PlanDecision.PlannedTask("T2", "Implement b", "brief", "orders",
                "main", "b", List.of("src/x.js"), "test/x.test.js", List.of());

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, List.of(quoted, prefixed), history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title contains a quotation mark")
                .hasMessageContaining("starts with \"implement\" or \"task\"");
    }

    @Test
    void rejectsAnAreaNotInTheStorysAreas() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "billing", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("area \"billing\" is not one of the story's areas");
    }

    @Test
    void rejectsMoreThanEightTouches() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<String> nineFiles = List.of("f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9");
        Map<String, Boolean> catalog = new java.util.LinkedHashMap<>();
        for (String f : nineFiles) {
            catalog.put(f, true);
        }
        catalog.put("test/x.test.js", true);
        List<PlanConsultation.Exchange> history = List.of(exchange(1, "a".repeat(40), catalog));
        List<PlanDecision.PlannedTask> drafts = List.of(task("T1", "a", "orders", nineFiles, "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, REPOS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must list 1-8 repo-relative paths");
    }

    @Test
    void rejectsAnAreaNotServedByTheTasksRepo() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<RepoConfig> repos = List.of(
                new RepoConfig("main", "local-git", "url", "main", "openspec", List.of(), true),
                new RepoConfig("secondary", "local-git", "url", "main", "openspec", List.of("web"), false));
        List<PlanConsultation.Exchange> history = List.of(
                exchange(1, "secondary", "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", "secondary", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, repos))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("area \"orders\" is not served by repo \"secondary\"");
    }

    @Test
    void rejectsACrossRepoTouchesPathWithNoCatalogEntryForTheTasksRepo() {
        PoHandoff po = po(List.of("a"), List.of("orders"));
        List<RepoConfig> repos = List.of(
                new RepoConfig("main", "local-git", "url", "main", "openspec", List.of(), true),
                new RepoConfig("secondary", "local-git", "url", "main", "openspec", List.of(), false));
        // The catalog evidence is recorded under repo "web" only - it must not satisfy a task
        // declared on repo "secondary" (cross-repo entries never cross-satisfy).
        List<PlanConsultation.Exchange> history = List.of(
                exchange(1, "web", "a".repeat(40), Map.of("src/x.js", true, "test/x.test.js", true)));
        List<PlanDecision.PlannedTask> drafts = List.of(
                task("T1", "a", "orders", "secondary", List.of("src/x.js"), "test/x.test.js", List.of()));

        assertThatThrownBy(() -> PlanAssembler.assemble(STORY, po, drafts, history, repos))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no consultation catalog entry");
    }

    @Test
    void isValidRepoPathRejectsAbsoluteBackslashParentAndReservedFirstSegments() {
        assertThat(PlanAssembler.isValidRepoPath("src/export.ts")).isTrue();
        assertThat(PlanAssembler.isValidRepoPath("/etc/passwd")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath("C:\\evil")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath("../secret")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath("src/../../escape")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath(".git/config")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath(".pdlc/consultation.json")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath("")).isFalse();
        assertThat(PlanAssembler.isValidRepoPath(null)).isFalse();
    }
}
