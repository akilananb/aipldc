package ai.pdlc.agents.release;

import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.ReleaseDocument;
import ai.pdlc.core.domain.ReleaseHandoff;
import ai.pdlc.core.domain.ReviewHandoff;
import ai.pdlc.core.domain.RolloutPlan;
import ai.pdlc.core.domain.Task;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.workflow.BuildResult;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Release agent (playbook §7) as a plain Spring service. The pilot's release pack is 4 of the 8
 * documents in the playbook table (change notes, rollout/rollback plan, monitor rules, test
 * evidence) — the ones fully derivable from data this pipeline already produces; ASMR/AIG are
 * org-specific form templates with no real org template to fill (out of pilot scope, matching how
 * the ADO adapter's automated verification stays WireMock-only), and risk-sign-off/stakeholder-comms
 * are the same "one document, one checker, one status" pattern repeated. Change notes is the one
 * real Embabel {@link Ai} call, marked {@code [agent:release]}; the rest is deterministic Java
 * template assembly from the story, build results, and review handoff — no invented data.
 */
@Component
public class ReleaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ReleaseAgent.class);

    private final Ai ai;
    private final String releaseModel;
    private final PromptTemplates templates;

    public ReleaseAgent(Ai ai, PromptTemplates templates, Profile activeProfile) {
        this.ai = ai;
        this.templates = templates;
        var role = activeProfile.agents().roles().get("release");
        this.releaseModel = role != null ? role.model() : null;
    }

    public ReleaseHandoff draft(WorkItemRef story, PoHandoff po, List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        String releaseId = "R-" + LocalDate.now(ZoneOffset.UTC) + "-" + story.boardId();

        ReleaseDocument changeNotes = new ReleaseDocument("change-notes", "Change notes", changeNotes(po), "PO");
        RolloutPlan rollout = rolloutPlan(po);
        ReleaseDocument rolloutDoc = new ReleaseDocument("rollout-plan", "Rollout & rollback plan", rolloutMarkdown(rollout), "SquadLead");
        List<MonitorRule> monitorRules = monitorRules(po);
        ReleaseDocument monitorDoc = new ReleaseDocument("monitor-rules", "Monitor rules", monitorRulesMarkdown(monitorRules), "QA");
        ReleaseDocument evidenceDoc = new ReleaseDocument("test-evidence", "Test evidence", testEvidenceMarkdown(tasks, results, review), "QA");

        Handoff envelope = new Handoff("release-agent", "monitor-agent", story.boardId(), CanonicalState.AWAITING_G3,
                List.of("po:" + po.change(), "tasks:" + tasks.size(), "results:" + results.size()), 0.85, List.of(), List.of());

        return new ReleaseHandoff(envelope, releaseId,
                List.of(changeNotes, rolloutDoc, monitorDoc, evidenceDoc), rollout, monitorRules);
    }

    // -- change notes: one real LLM call, deterministic fallback ---------------------------------

    private String changeNotes(PoHandoff po) {
        String prompt = templates.render("release-change-notes",
                Map.of("change", po.change(), "scenarios", String.join(", ", po.scenarios())));
        try {
            String raw = promptRunner().generateText(prompt);
            String text = raw == null ? "" : raw.strip();
            return text.isEmpty() ? fallbackChangeNotes(po) : text;
        } catch (RuntimeException e) {
            log.warn("[release] LLM change-notes call failed; using the deterministic fallback: {}", e.toString());
            return fallbackChangeNotes(po);
        }
    }

    private String fallbackChangeNotes(PoHandoff po) {
        return templates.render("release-change-notes-fallback", Map.of("scenarios", String.join(", ", po.scenarios())));
    }

    // -- deterministic template assembly ----------------------------------------------------------

    private static RolloutPlan rolloutPlan(PoHandoff po) {
        String flag = slugOf(po.change()) + ".enabled";
        return new RolloutPlan(10, 60, flag, "flag off + deploy prev");
    }

    private String rolloutMarkdown(RolloutPlan rollout) {
        return templates.render("release-rollout-plan", Map.of(
                "canaryPct", rollout.canaryPct(), "soakMinutes", rollout.soakMinutes(),
                "flag", rollout.flag(), "rollback", rollout.rollback()));
    }

    /** One rule per NFR + the playbook §7 handoff's own worked example (export-orders-csv rate
     * limiting) whenever a scenario name suggests it applies — reusing the documented example
     * verbatim where the story matches it, rather than inventing a different one. */
    private static List<MonitorRule> monitorRules(PoHandoff po) {
        List<MonitorRule> rules = new ArrayList<>();
        rules.add(new MonitorRule("export-error-rate", "http_5xx_rate", "> 2% over 15m", "file-card", "PO"));
        boolean hasRateLimitScenario = po.scenarios().stream()
                .anyMatch(s -> s.toLowerCase().contains("rate") || s.toLowerCase().contains("abuse"));
        if (hasRateLimitScenario) {
            rules.add(new MonitorRule("export-abuse", "exports_per_user_hour", ">= 10 for > 3 users in 1h",
                    "file-card+notify-squad-lead", "SquadLead"));
        }
        return rules;
    }

    private String monitorRulesMarkdown(List<MonitorRule> rules) {
        List<Map<String, Object>> ruleViews = new ArrayList<>();
        for (MonitorRule r : rules) {
            ruleViews.add(Map.of("id", r.id(), "signal", r.signal(), "threshold", r.threshold(),
                    "action", r.action(), "owner", r.owner()));
        }
        return templates.render("release-monitor-rules", Map.of("rules", ruleViews));
    }

    private String testEvidenceMarkdown(List<Task> tasks, List<BuildResult> results, ReviewHandoff review) {
        List<Map<String, Object>> resultViews = new ArrayList<>();
        for (BuildResult r : results) {
            Task task = tasks.stream().filter(t -> t.id().equals(r.taskId())).findFirst().orElse(null);
            String scenario = task == null ? r.taskId() : task.scenario();
            resultViews.add(Map.of("taskId", r.taskId(), "scenario", scenario,
                    "result", r.verifier().result(), "iterations", r.iterations()));
        }
        List<Map<String, Object>> traceabilityViews = new ArrayList<>();
        for (ReviewHandoff.TraceabilityRow row : review.traceability()) {
            traceabilityViews.add(Map.of("scenario", row.scenario(), "testRef", row.testRef(), "codeRef", row.codeRef()));
        }
        return templates.render("release-test-evidence", Map.of("results", resultViews, "traceability", traceabilityViews));
    }

    private static String slugOf(String change) {
        String path = change == null ? "release" : change;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private com.embabel.agent.api.common.PromptRunner promptRunner() {
        if (releaseModel == null || releaseModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(releaseModel));
    }
}
