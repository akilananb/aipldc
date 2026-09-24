package ai.pdlc.agents.plan;

import ai.pdlc.agents.activities.ProjectContext;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PlanDecision;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItemRef;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The reasoning Plan Agent (§ "Add agent-consultation plan" — replaces the old one-shot
 * deterministic task-breakdown planner). One reasoning step per call: asks the repository
 * consultant evidence-backed questions (CONSULT), proposes a final task breakdown once evidence
 * is sufficient (FINALIZE), or stops naming a missing prerequisite (BLOCKED) — never both in one
 * call. {@code ai.pdlc.core.workflow.PlanningLoop} owns every deterministic transition; this class
 * only ever returns one {@link PlanDecision}. One real Embabel {@link Ai} call, marked
 * {@code [agent:plan]}; the decision-shape parse/validation is deterministic Java — see {@link #parse}.
 */
@Component
public class PlanAgent {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Wire shape of the LLM's decision response — camelCase, matching {@link PlanDecision}'s
     * Java field names one-to-one (no snake_case translation, per the shared Java/TypeScript wire
     * convention). */
    private record DecisionDto(String action, String reason, String repo, List<String> questions, List<String> paths,
                                List<PlannedTaskDto> tasks) {
    }

    private record PlannedTaskDto(String id, String title, String description, String area, String repo,
                                   String scenario, List<String> touches, String testPath, List<String> blockedBy) {
    }

    private final Ai ai;
    private final PromptTemplates templates;
    private final ProjectDirectory projects;
    private final String planModel;

    public PlanAgent(Ai ai, PromptTemplates templates, Profile activeProfile, ProjectDirectory projects) {
        this.ai = ai;
        this.templates = templates;
        this.projects = projects;
        var role = activeProfile.agents().roles().get("plan");
        this.planModel = role != null ? role.model() : null;
    }

    public PlanDecision nextStep(WorkItemRef story, PoHandoff po, String storyMarkdown, List<RepoConfig> repos,
                                  List<PlanConsultation.Exchange> history, List<String> feedback,
                                  boolean canConsult) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("storyMarkdown", storyMarkdown == null ? "" : storyMarkdown);
        view.put("change", po.change());
        view.put("areas", po.areas());
        view.put("scenarios", po.scenarios());
        List<String> nfrLines = formatNfr(po.nfr());
        view.put("nfr", nfrLines);
        view.put("hasNfr", !nfrLines.isEmpty());
        view.put("repos", formatRepos(repos));
        view.put("history", formatHistory(history));
        view.put("hasHistory", history != null && !history.isEmpty());
        view.put("feedback", feedback == null ? List.of() : feedback);
        view.put("hasFeedback", feedback != null && !feedback.isEmpty());
        view.put("canConsult", canConsult);
        view.put("project", ProjectContext.view(projects.project(story.profile())));

        String prompt = templates.render("plan-next-step", view);
        String raw = stripCodeFence(promptRunner().generateText(prompt));

        DecisionDto dto;
        try {
            dto = JSON.readValue(raw, DecisionDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed plan decision JSON response for " + story + ": " + e.getMessage(), e);
        }
        return parse(dto, story);
    }

    private static List<Map<String, Object>> formatRepos(List<RepoConfig> repos) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RepoConfig r : repos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("primary", r.primary());
            m.put("areas", r.areas().isEmpty() ? "(any area)" : String.join(", ", r.areas()));
            result.add(m);
        }
        return result;
    }


    /** Strict decision-shape parse — never synthesizes a successful plan when the model's output
     * is malformed or an action's required fields are missing; {@code PlanningLoop} still applies
     * every business-level bound (first-decision-must-CONSULT, consultation budget, etc). */
    static PlanDecision parse(DecisionDto dto, WorkItemRef story) {
        if (dto == null || dto.action() == null || dto.action().isBlank()) {
            throw new IllegalStateException("Plan decision response for " + story + " is missing an action");
        }
        PlanDecision.Action action;
        try {
            action = PlanDecision.Action.valueOf(dto.action().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Plan decision response for " + story + " has unknown action: " + dto.action(), e);
        }
        if (dto.reason() == null || dto.reason().isBlank()) {
            throw new IllegalStateException("Plan decision response for " + story + " is missing a nonblank reason");
        }

        List<String> questions = dto.questions() == null ? List.of() : dto.questions();
        List<String> paths = dto.paths() == null ? List.of() : dto.paths();
        List<PlanDecision.PlannedTask> tasks = new ArrayList<>();
        if (dto.tasks() != null) {
            for (PlannedTaskDto t : dto.tasks()) {
                tasks.add(new PlanDecision.PlannedTask(t.id(), t.title(), t.description(), t.area(), t.repo(), t.scenario(),
                        t.touches(), t.testPath(), t.blockedBy()));
            }
        }

        if (action == PlanDecision.Action.CONSULT && questions.isEmpty()) {
            throw new IllegalStateException("Plan decision response for " + story + " is CONSULT with no questions");
        }
        if (action == PlanDecision.Action.FINALIZE && tasks.isEmpty()) {
            throw new IllegalStateException("Plan decision response for " + story + " is FINALIZE with no tasks");
        }

        return new PlanDecision(action, dto.reason(), dto.repo(), questions, paths, tasks);
    }

    private static List<String> formatNfr(Map<String, Object> nfr) {
        List<String> lines = new ArrayList<>();
        if (nfr != null) {
            for (Map.Entry<String, Object> e : nfr.entrySet()) {
                lines.add(e.getKey() + ": " + e.getValue());
            }
        }
        return lines;
    }

    private static String formatHistory(List<PlanConsultation.Exchange> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (PlanConsultation.Exchange exchange : history) {
            sb.append("### Round ").append(exchange.round()).append(" · repo ").append(exchange.repoId()).append('\n');
            sb.append("Questions asked:\n");
            for (String q : exchange.questions()) {
                sb.append("- ").append(q).append('\n');
            }
            PlanConsultation.Report report = exchange.report();
            if (report != null) {
                sb.append("Consultant findings:\n").append(report.findingsMarkdown()).append('\n');
                sb.append("File catalog (path: exists/new):\n");
                for (PlanConsultation.FileEvidence fe : report.files()) {
                    sb.append("- ").append(fe.path()).append(": ").append(fe.exists() ? "exists" : "new").append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Mirrors {@code GrillAgent.stripCodeFence}/{@code PoAgent.stripCodeFence} — same convention,
     * applied to this agent's own response. */
    private static String stripCodeFence(String s) {
        String out = s;
        if (out.startsWith("```")) {
            int firstNewline = out.indexOf('\n');
            out = firstNewline >= 0 ? out.substring(firstNewline + 1) : out.substring(3);
        }
        if (out.endsWith("```")) {
            out = out.substring(0, out.length() - 3);
        }
        return out.strip();
    }

    private PromptRunner promptRunner() {
        if (planModel == null || planModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(planModel));
    }
}
