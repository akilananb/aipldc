package ai.pdlc.agents.review;

import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.ReviewFinding;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BuildResult;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Review agent (build-order phase 3, {@code docs/agent-playbook.md}'s review-agent role) as a
 * plain Spring service. Traceability (scenario → test → code) and blocker/should findings —
 * verifier-red, scope violations, build-loop escalations — are deterministic Java ({@link
 * #buildTraceability}/{@link #buildFindings}, the standalone unit-test surface), since they must be
 * trustworthy gate-2 evidence (the pre-decided guard-method fallback). A short qualitative summary
 * is the one real Embabel {@link Ai} call, marked {@code [agent:review]} for the stub-llm; on
 * failure it is simply omitted (a missing summary is not a blocker).
 */
@Component
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    private final Ai ai;
    private final String reviewModel;
    private final PromptTemplates templates;

    public ReviewAgent(Ai ai, PromptTemplates templates, Profile activeProfile) {
        this.ai = ai;
        this.templates = templates;
        var role = activeProfile.agents().roles().get("review");
        this.reviewModel = role != null ? role.model() : null;
    }

    public ReviewHandoff review(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results) {
        List<ReviewHandoff.TraceabilityRow> traceability = buildTraceability(tasks, results);
        List<ReviewFinding> findings = new ArrayList<>(buildFindings(tasks, results));

        ReviewFinding summary = summaryFinding(po, tasks, results);
        if (summary != null) {
            findings.add(summary);
        }

        Handoff envelope = new Handoff("review-agent", "gate-2", story.boardId(), CanonicalState.AWAITING_G2,
                List.of("po:" + po.change(), "tasks:" + tasks.size(), "results:" + results.size()),
                0.85, List.of(), List.of());
        return new ReviewHandoff(envelope, traceability, findings);
    }

    // -- deterministic ---------------------------------------------------------------------------

    /** One row per completed task: scenario → test tag → touched files. */
    static List<ReviewHandoff.TraceabilityRow> buildTraceability(List<Task> tasks, List<BuildResult> results) {
        List<ReviewHandoff.TraceabilityRow> traceability = new ArrayList<>();
        for (BuildResult result : results) {
            Task task = taskById(tasks, result.taskId());
            String testRef = task.testPath() + " :: scenario: " + task.scenario();
            String codeRef = String.join(", ", task.touches());
            traceability.add(new ReviewHandoff.TraceabilityRow(task.scenario(), testRef, codeRef));
        }
        return traceability;
    }

    /** Blocker on scope violations and non-green verifier results; should on any build-loop
     * escalation (playbook §4 "Stop conditions" — a WIP branch + escalation note is a correct
     * outcome, not a workflow failure, but gate 2 still needs to see it). */
    static List<ReviewFinding> buildFindings(List<Task> tasks, List<BuildResult> results) {
        List<ReviewFinding> findings = new ArrayList<>();
        for (BuildResult result : results) {
            Task task = taskById(tasks, result.taskId());
            if (!result.verifier().scopeOk()) {
                findings.add(new ReviewFinding(ReviewFinding.Severity.BLOCKER, "scope",
                        "Task " + task.id() + " (" + task.scenario() + ") edited files outside its touches list: "
                                + result.verifier().notes(), null));
            } else if (!"green".equals(result.verifier().result())) {
                findings.add(new ReviewFinding(ReviewFinding.Severity.BLOCKER, "verifier",
                        "Task " + task.id() + " (" + task.scenario() + ") did not reach green: "
                                + result.verifier().notes(), task.touches().isEmpty() ? null : task.touches().get(0)));
            }
            if (result.escalation() != null) {
                findings.add(new ReviewFinding(ReviewFinding.Severity.SHOULD, "escalation",
                        "Task " + task.id() + " escalated: " + result.escalation(), null));
            }
        }
        return findings;
    }

    private static Task taskById(List<Task> tasks, String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No such task: " + id));
    }

    // -- LLM (real Embabel Ai API) ---------------------------------------------------------------

    private ReviewFinding summaryFinding(PoHandoff po, List<Task> tasks, List<BuildResult> results) {
        List<Map<String, Object>> resultViews = new ArrayList<>();
        for (BuildResult r : results) {
            Task task = taskById(tasks, r.taskId());
            resultViews.add(Map.of("taskId", task.id(), "scenario", task.scenario(),
                    "result", r.verifier().result(), "iterations", r.iterations()));
        }
        String prompt = templates.render("review-summary", Map.of("change", po.change(), "results", resultViews));
        try {
            String raw = promptRunner().generateText(prompt);
            String text = raw == null ? "" : raw.strip();
            return text.isEmpty() ? null : new ReviewFinding(ReviewFinding.Severity.NIT, "summary", text, null);
        } catch (RuntimeException e) {
            log.warn("[review] LLM summary call failed; omitting the summary finding: {}", e.toString());
            return null;
        }
    }

    private com.embabel.agent.api.common.PromptRunner promptRunner() {
        if (reviewModel == null || reviewModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(reviewModel));
    }
}
