package ai.pdlc.agents.grill;

import ai.pdlc.agents.activities.AgentContext;
import ai.pdlc.agents.activities.ProjectContext;
import ai.pdlc.agents.config.PortRegistry;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.GrillRound;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.BoardCommentEvent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grill agent (playbook §1) as a plain Spring service, driving an adaptive interview
 * (ADAPTIVE_GRILL_PLAN.md): {@link #nextRound} asks the currently independent frontier with a
 * recommendation per question, folds in accumulated history, and returns either more OPEN
 * questions or an empty frontier plus a candidate summary. The typed-question generation runs
 * through Embabel's {@link Ai} (real API, {@code generateText}), with the pinned {@code
 * grill-me}/{@code grilling} skill instructions ({@link GrillSkills#instructions()}) embedded
 * verbatim in the rendered prompt (not attached as a live {@code withReference} tool source — see
 * {@link #promptRunner()}) and an {@code [agent:grill]} marker the stub-llm matches on; the
 * mandatory risk question and answer evaluation are deterministic Java.
 *
 * <p>It never answers its own questions: on re-run it marks each question {@code answered} (from a
 * human {@link BoardCommentEvent} that references it with {@code <id>: <text>}) or {@code parked}
 * (referenced with {@code <id>: park}); a question not referenced in a batch stays {@code open} —
 * only an explicit park closes it, so nothing is silently dropped.
 */
@Component
public class GrillAgent {

    private static final Logger log = LoggerFactory.getLogger(GrillAgent.class);

    /** pii / auth / payment keywords that force a mandatory RISK question (playbook §1 "Validates"). */
    private static final List<String> RISK_KEYWORDS = List.of("pii", "payment", "auth", "password", "credit card", "token");

    private static final Pattern ANSWER_MARKER = Pattern.compile("(?i)\\b((?:q|po|h)\\d+)\\s*:");

    private static final String PARK_KEYWORD = "park";

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Wire shape of the stub/LLM adaptive-round response; snake_case via {@link #JSON}'s naming
     * strategy. Questions carry no id — ids are application-owned ({@link GrillQuestion#nextGrillId}). */
    public record GrillResponse(String typeDecision, List<QuestionDto> questions, List<String> constraintsHit, String summary) {
    }

    public record QuestionDto(String category, String question, String recommendation, String evidence) {
    }

    private final Ai ai;
    private final PortRegistry ports;
    private final ProjectDirectory projects;
    private final PromptTemplates templates;
    private final Profile activeProfile;
    private final GrillSkills skills;
    private final String grillModel;
    /** {@code agents.roles.grill.budget_tokens} from {@code pdlc.yaml}, applied as the request's
     * {@code max_tokens} when a model is configured — {@code null} otherwise (the default-LLM
     * branch below never had a budget to apply anyway). Every other role's {@code budget_tokens}
     * is still parsed but unapplied — out of scope for this change. */
    private final Integer grillMaxTokens;

    public GrillAgent(Ai ai, PortRegistry ports, ProjectDirectory projects, PromptTemplates templates,
                       Profile activeProfile, GrillSkills skills) {
        this.ai = ai;
        this.ports = ports;
        this.projects = projects;
        this.templates = templates;
        this.activeProfile = activeProfile;
        this.skills = skills;
        var role = activeProfile.agents().roles().get("grill");
        this.grillModel = role != null ? role.model() : null;
        this.grillMaxTokens = role != null && role.budgetTokens() != null ? role.budgetTokens().intValue() : null;
    }


    public GrillHandoff evaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
        if (previous == null) {
            // Pre-patch Temporal executions (Workflow.getVersion DEFAULT_VERSION) still call this
            // three-argument activity for the initial batch; adaptive rounds call nextRound
            // directly. No adaptive generation is added to the answer-folding branch below.
            return nextRound(item, null).handoff();
        }
        return evaluateAnswers(previous, newComments == null ? List.of() : newComments);
    }

    // -- deterministic ---------------------------------------------------------------------------

    /** True when {@code title}/{@code description} contains a pii/auth/payment keyword. */
    public static boolean riskKeywordHit(String title, String description) {
        String haystack = ((title == null ? "" : title) + " " + (description == null ? "" : description)).toLowerCase();
        for (String kw : RISK_KEYWORDS) {
            if (haystack.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** The mandatory risk question, citing {@code constraintsEvidence} when a real citation was
     * found in the repo's constraints file, else {@link GrillQuestion#ASSUMPTION_CHECK}. Wording is
     * generalized to whichever sensitive-scope keyword actually hit, not hardcoded to one domain. */
    public static GrillQuestion mandatoryRiskQuestion(String id, String title, String description, String constraintsEvidence) {
        String haystack = ((title == null ? "" : title) + " " + (description == null ? "" : description)).toLowerCase();
        String scope = RISK_KEYWORDS.stream().filter(haystack::contains).findFirst().orElse("sensitive");
        String evidence = constraintsEvidence != null && !constraintsEvidence.isBlank() ? constraintsEvidence : GrillQuestion.ASSUMPTION_CHECK;
        return new GrillQuestion(id, GrillQuestion.Category.RISK,
                "This feature touches " + scope + " data. Log and rate-limit access accordingly?",
                evidence, GrillQuestion.Status.OPEN, null, null);
    }

    /** Adds a mandatory RISK question iff the keyword hit and none is already present (answered or
     * parked counts as present — never regenerated once settled), using {@link GrillQuestion#ASSUMPTION_CHECK}. */
    public static List<GrillQuestion> ensureRiskQuestion(List<GrillQuestion> questions, String title, String description) {
        return ensureRiskQuestion(questions, title, description, null);
    }

    /** Overload taking a real constraints-file citation, when the caller found one (ADAPTIVE_GRILL_PLAN.md
     * step 6); the shared {@link GrillQuestion#nextGrillId} allocator keeps this consistent with
     * every model-generated question in the same round. */
    public static List<GrillQuestion> ensureRiskQuestion(List<GrillQuestion> questions, String title, String description, String constraintsEvidence) {
        boolean hasRisk = questions.stream().anyMatch(q -> q.category() == GrillQuestion.Category.RISK);
        if (hasRisk || !riskKeywordHit(title, description)) {
            return questions;
        }
        List<GrillQuestion> out = new ArrayList<>(questions);
        out.add(mandatoryRiskQuestion(GrillQuestion.nextGrillId(questions), title, description, constraintsEvidence));
        return out;
    }

    /** Best-effort real citation from the repo's constraints file: the first heading whose text
     * looks risk/data-classification related, slugified into a {@code docs/constraints.md#<slug>}
     * anchor; {@code null} (falls back to assumption-check) otherwise. */
    static String findConstraintsCitation(String constraintsMd) {
        if (constraintsMd == null || constraintsMd.isBlank()) {
            return null;
        }
        for (String line : constraintsMd.lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("#")) {
                continue;
            }
            String heading = trimmed.replaceFirst("^#+\\s*", "");
            String lower = heading.toLowerCase();
            if (lower.contains("pii") || lower.contains("data") || lower.contains("risk")
                    || lower.contains("security") || lower.contains("classification")) {
                String slug = lower.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
                return "docs/constraints.md#" + slug;
            }
        }
        return null;
    }

    /** Maps each {@code <id>: <body>} marker in one comment's text to its (trimmed) body — lower-
     * cased id, in the order the markers appear; a body empty after trimming is skipped (the
     * question stays open); a repeated id within the same comment: the last occurrence wins. */
    static Map<String, String> parseAnswers(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = ANSWER_MARKER.matcher(text);
        List<Integer> matchStarts = new ArrayList<>();
        List<Integer> bodyStarts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            matchStarts.add(m.start());
            bodyStarts.add(m.end());
            ids.add(m.group(1).toLowerCase());
        }
        for (int i = 0; i < ids.size(); i++) {
            int bodyEnd = i + 1 < matchStarts.size() ? matchStarts.get(i + 1) : text.length();
            String body = text.substring(bodyStarts.get(i), bodyEnd).strip();
            if (!body.isEmpty()) {
                out.put(ids.get(i), body);
            }
        }
        return out;
    }

    /** Folds every {@code <id>: text|park} marker across {@code newComments} (later comment wins per
     * id, remembering that comment's author); a question referenced with body {@code park}/{@code
     * parked} (case-insensitive) is explicitly parked, referenced with any other body is answered,
     * unreferenced stays {@code open} — no auto-park (playbook §1, revised: humans control park).
     * The reserved intake-confirmation question (evidence {@link GrillQuestion#INTAKE_CONFIRMATION_EVIDENCE})
     * is never auto-parked into {@code parked scope} by this fold — {@code park}/{@code parked}
     * simply leaves it OPEN, matching every other question's park handling but never counted as
     * approving intake (the workflow's own confirmation check owns that decision). */
    static GrillHandoff evaluateAnswers(GrillHandoff previous, List<BoardCommentEvent> newComments) {
        Map<String, String> bodies = new LinkedHashMap<>();
        Map<String, String> authors = new LinkedHashMap<>();
        for (BoardCommentEvent c : newComments) {
            for (Map.Entry<String, String> e : parseAnswers(c.text()).entrySet()) {
                bodies.put(e.getKey(), e.getValue());
                authors.put(e.getKey(), c.author());
            }
        }
        List<GrillQuestion> updated = new ArrayList<>();
        List<String> newlyParked = new ArrayList<>();
        for (GrillQuestion q : previous.questions()) {
            if (q.status() != GrillQuestion.Status.OPEN) {
                updated.add(q);
                continue;
            }
            String body = bodies.get(q.id().toLowerCase());
            if (body == null) {
                updated.add(q);
            } else if (body.equalsIgnoreCase(PARK_KEYWORD) || body.equalsIgnoreCase("parked")) {
                if (GrillQuestion.INTAKE_CONFIRMATION_EVIDENCE.equals(q.evidence())) {
                    // Reserved confirmation question: parking it is a no-op — it stays OPEN and
                    // never approves intake (ADAPTIVE_GRILL_PLAN.md step 5).
                    updated.add(q);
                } else {
                    updated.add(q.parked());
                    newlyParked.add(q.id());
                }
            } else {
                updated.add(q.withAnswer(body, authors.get(q.id().toLowerCase())));
            }
        }
        List<String> parked = new ArrayList<>(previous.parked());
        for (String id : newlyParked) {
            if (!parked.contains(id)) {
                parked.add(id);
            }
        }
        List<String> constraints = previous.constraintsHit() == null ? List.of() : previous.constraintsHit();
        return new GrillHandoff(previous.envelope(), previous.typeDecision(), updated, parked, constraints);
    }

    // -- LLM (real Embabel Ai API) ---------------------------------------------------------------

    /** One adaptive-intake reasoning round (ADAPTIVE_GRILL_PLAN.md step 3): {@code previous == null}
     * starts intake; otherwise every question in {@code previous} must already be resolved. Malformed
     * or invalid model output and LLM errors throw, letting Temporal retry — never interpreted as an
     * empty completed frontier. */
    public GrillRound nextRound(WorkItemRef item, GrillHandoff previous) {
        if (previous != null && !previous.allQuestionsResolved()) {
            throw new IllegalStateException("Cannot start a new grill round while questions remain open: " + item);
        }

        Profile project = projects.project(item.profile());
        BoardPort board = ports.board(item.profile());
        RepoPort repo = ports.primaryRepo(item.profile());
        WorkItem workItem = AgentContext.readWorkItem(board, item);
        List<Comment> boardComments = AgentContext.readComments(board, item);
        String title = workItem == null ? item.boardId() : safe(workItem.title());
        String description = workItem == null ? "" : safe(workItem.description());

        String defaultBranch = project.repo().defaultBranch();
        String readme = AgentContext.readFile(repo, defaultBranch, "README.md");
        String constraints = AgentContext.readFile(repo, defaultBranch, "docs/constraints.md");
        String specConfig = AgentContext.readFile(repo, defaultBranch, project.repo().specDir() + "/config.yaml");

        List<GrillQuestion> history = previous == null ? List.of() : previous.questions();
        String nextId = GrillQuestion.nextGrillId(history);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("skillInstructions", skills.instructions());
        view.put("title", title);
        view.put("description", description);
        view.put("comments", formatComments(boardComments));
        view.put("readme", readme == null ? "(unavailable)" : readme);
        view.put("constraints", constraints == null ? "(unavailable)" : constraints);
        view.put("specConfig", specConfig == null ? "(unavailable)" : specConfig);
        view.put("history", formatHistory(history));
        view.put("nextId", nextId);
        view.put("project", ProjectContext.view(project));
        String prompt = templates.render("grill-questions", view);

        String raw = stripCodeFence(promptRunner().generateText(prompt));
        GrillResponse response;
        try {
            response = JSON.readValue(raw, GrillResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed grill round JSON response for " + item + ": " + e.getMessage(), e);
        }
        if (response.questions() == null || response.constraintsHit() == null) {
            throw new IllegalStateException("Grill round response for " + item + " is missing the questions/constraints_hit arrays");
        }

        List<GrillQuestion> updated = new ArrayList<>(history);
        int newCount = 0;
        for (QuestionDto dto : response.questions()) {
            GrillQuestion.Category category = parseCategory(dto.category());
            requireNonBlank(dto.question(), "question");
            requireNonBlank(dto.recommendation(), "recommendation");
            requireNonBlank(dto.evidence(), "evidence");
            String id = GrillQuestion.nextGrillId(updated);
            String text = dto.question().strip() + "\n\nRecommended answer: " + dto.recommendation().strip();
            updated.add(new GrillQuestion(id, category, text, dto.evidence().strip(), GrillQuestion.Status.OPEN, null, null));
            newCount++;
        }

        int beforeRisk = updated.size();
        updated = ensureRiskQuestion(updated, title, description, findConstraintsCitation(constraints));
        boolean riskAdded = updated.size() > beforeRisk;
        boolean frontierEmpty = newCount == 0 && !riskAdded;

        if (frontierEmpty && (response.summary() == null || response.summary().isBlank())) {
            throw new IllegalStateException("Grill round response for " + item + " returned an empty frontier without a nonblank summary");
        }

        List<String> constraintsHit = mergeConstraints(previous, response.constraintsHit());
        // The feature type is classified once, at intake start; later rounds keep it stable rather
        // than letting a dependent-question round silently reclassify story/epic/bug/duplicate.
        String typeDecision = previous != null ? previous.typeDecision()
                : (response.typeDecision() == null ? "story" : response.typeDecision());

        Handoff envelope = previous != null ? previous.envelope()
                : new Handoff("grill-agent", "po-agent", item.boardId(), CanonicalState.READY_FOR_STORY,
                        List.of("board:" + item.profile() + ":" + item.boardId(), "board-comments:" + boardComments.size()),
                        0.8, List.of(), List.of());

        GrillHandoff handoff = new GrillHandoff(envelope, typeDecision, updated,
                previous == null ? List.of() : previous.parked(), constraintsHit);
        return new GrillRound(handoff, frontierEmpty ? response.summary().strip() : response.summary());
    }

    /** Strips a leading/trailing ``` or ```json Markdown code fence some LLMs wrap the JSON
     * response in despite the prompt asking for raw JSON; a no-op when no fence is present.
     * Mirrors {@code PoAgent.stripCodeFence} — same convention, applied to this agent's own
     * response. */
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

    private static GrillQuestion.Category parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Grill question is missing its category");
        }
        GrillQuestion.Category category;
        try {
            category = GrillQuestion.Category.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown grill question category: " + raw, e);
        }
        if (category == GrillQuestion.Category.BUILD) {
            throw new IllegalStateException("Grill-generated questions may never use category BUILD");
        }
        return category;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Grill question " + field + " must be nonblank");
        }
    }

    private static List<String> mergeConstraints(GrillHandoff previous, List<String> newHits) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (previous != null && previous.constraintsHit() != null) {
            merged.addAll(previous.constraintsHit());
        }
        merged.addAll(newHits);
        return List.copyOf(merged);
    }

    private static String formatComments(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Comment c : comments) {
            sb.append("- ").append(c.by()).append(": ").append(c.text()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String formatHistory(List<GrillQuestion> history) {
        if (history.isEmpty()) {
            return "(none yet)";
        }
        StringBuilder sb = new StringBuilder();
        for (GrillQuestion q : history) {
            sb.append("- ").append(q.id()).append(" [").append(q.category().wireValue()).append("] ")
                    .append(q.status().wireValue()).append(": ").append(q.question());
            if (q.answer() != null) {
                sb.append(" -> ").append(q.answer());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Deliberately does NOT attach {@code skills.reference()} via {@code PromptRunner.withReference}:
     * {@link GrillSkills#instructions()} is already embedded verbatim in the rendered prompt
     * ({@code {{{skillInstructions}}}} in grill-questions.mustache), so the model already has the
     * full grill-me/grilling instructions as plain text. Attaching the live {@code Skills}
     * reference ALSO exposes its {@code @LlmTool} methods (activate/listResources/readResource) as
     * real callable tools; observed live behavior showed the model entering a runaway multi-minute
     * tool-calling loop exploring those tools instead of answering (Embabel's {@code
     * disable-model-invocation} skill frontmatter flag is ignored by its parser, so it cannot
     * suppress this) - a real, harmful regression, not the plan's intended "eager activation"
     * behavior. Prompt-injected instructions alone already satisfy "every call receives the actual
     * interview instructions"; no tool exposure is needed. */
    private PromptRunner promptRunner() {
        if (grillModel == null || grillModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        LlmOptions options = LlmOptions.withModel(grillModel);
        if (grillMaxTokens != null) {
            options = options.withMaxTokens(grillMaxTokens);
        }
        return ai.withLlm(options);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
