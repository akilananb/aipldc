package ai.pdlc.agents.po;

import ai.pdlc.agents.activities.AgentContext;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import ai.pdlc.core.workflow.PoDraftResult;
import ai.pdlc.core.workflow.StoryDraft;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PO agent (playbook §2) as a plain Spring service. Story/spec-delta text is produced through
 * Embabel's {@link Ai} ({@code generateText}) with {@code [agent:po]} / {@code [agent:po-revise]}
 * markers the stub-llm matches on; INVEST and DoR are deterministic Java validators (the pre-decided
 * guard-method fallback for {@code @Condition}) whose results populate {@link PoHandoff#invest()} /
 * {@link PoHandoff#dorUnmet()} — never hardcoded "pass".
 */
@Component
public class PoAgent {

    private static final Logger log = LoggerFactory.getLogger(PoAgent.class);

    private static final Pattern CHANGE = Pattern.compile("Change:\\s*(\\S+)");
    private static final Pattern NFR_BULLET = Pattern.compile("^\\s*-\\s*([^:]+):\\s*(.*)$");
    private static final Pattern STORY_SEPARATOR = Pattern.compile("(?m)^===STORY===\\s*$");

    /** First line of a PO agent follow-up reply — see {@code po-draft.mustache}'s {@code allowFollowUps} block. */
    static final String QUESTIONS_SENTINEL = "===QUESTIONS===";

    private static final com.fasterxml.jackson.databind.ObjectMapper FOLLOW_UP_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Wire shape of one follow-up question in the PO agent's {@code ===QUESTIONS===} JSON array. */
    record FollowUpDto(String category, String question, String evidence) {
    }

    private final Ai ai;
    private final BoardPort board;
    private final RepoPort repo;
    private final PromptTemplates templates;
    private final String poModel;

    public PoAgent(Ai ai, BoardPort board, RepoPort repo, PromptTemplates templates, Profile activeProfile) {
        this.ai = ai;
        this.board = board;
        this.repo = repo;
        this.templates = templates;
        var role = activeProfile.agents().roles().get("po");
        this.poModel = role != null ? role.model() : null;
    }

    public PoDraftResult draft(WorkItemRef item, GrillHandoff grill, boolean allowFollowUps) {
        WorkItem workItem = AgentContext.readWorkItem(board, item);
        String title = workItem == null ? item.boardId() : safe(workItem.title());
        String description = workItem == null ? "" : safe(workItem.description());
        String areaPath = workItem == null ? null : workItem.areaPath();

        Map<String, Object> view = new HashMap<>();
        view.put("title", title);
        view.put("description", description);
        view.put("allowFollowUps", allowFollowUps);
        if (grill != null) {
            view.put("grill", grillView(grill));
        }
        String prompt = templates.render("po-draft", view);

        String output = promptRunner().generateText(prompt);
        if (allowFollowUps) {
            List<GrillQuestion> followUps = parseFollowUps(output, nextPoIndex(grill));
            if (followUps != null && !followUps.isEmpty()) {
                return new PoDraftResult(List.of(), followUps);
            }
        }
        List<String> parts = new ArrayList<>();
        for (String part : STORY_SEPARATOR.split(output)) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        if (parts.isEmpty()) {
            parts.add(output.strip());
        }

        List<StoryDraft> drafts = new ArrayList<>();
        Set<String> seenChanges = new HashSet<>();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            // Only used as a per-part fallback when that part's own text has no title heading of
            // its own (see assemble); disambiguated so a multi-story split where every part lacks
            // one doesn't inject the identical title/slug into every story.
            String partTitle = i == 0 ? title : title + " (" + (i + 1) + ")";
            StoryDraft draft = assemble(part, item, null, item.boardId(), grill, areaPath, partTitle);
            if (!seenChanges.add(draft.handoff().change())) {
                draft = assemble(part, item, draft.handoff().change() + "-s" + (drafts.size() + 1), item.boardId(), grill, areaPath, partTitle);
                seenChanges.add(draft.handoff().change());
            }
            drafts.add(draft);
        }
        return new PoDraftResult(drafts, List.of());
    }

    public StoryDraft revise(WorkItemRef item, PoHandoff previous, List<Comment> comments) {
        List<Map<String, Object>> commentViews = new ArrayList<>();
        for (Comment c : comments) {
            commentViews.add(Map.of("target", c.target(), "text", c.text()));
        }

        // Best-effort: read the previous story so the LLM has the exact text (unavailable in the
        // local in-memory profile where the repo is a separate JVM from control-plane's).
        String previousStory = AgentContext.readFile(repo, "main", (previous.change() == null ? "" : previous.change()) + "/proposal.md");

        Map<String, Object> view = new HashMap<>();
        view.put("comments", commentViews);
        if (previousStory != null) {
            view.put("previousStory", Map.of("story", previousStory));
        }
        String prompt = templates.render("po-revise", view);

        String revised = promptRunner().generateText(prompt);
        String areaPath = previous.areas().isEmpty() ? null : previous.areas().get(0);
        // Title fallback: prefer the real title parsed from previousStory when the repo read above
        // succeeded (most accurate - it's this exact story's actual prior H1); when it didn't
        // (the common case in the local in-memory profile - see the comment above), the LLM never
        // saw the previous title and writes from the comments alone, typically emitting no H1 of
        // its own, so fall back to the story's current board title.
        String featureTitle = previousStory == null ? null : StoryParser.title(previousStory);
        if (featureTitle == null) {
            WorkItem storyItem = AgentContext.readWorkItem(board, item);
            featureTitle = storyItem == null ? null : storyItem.title();
        }
        return assemble(revised, item, previous.change(), previous.parent(), null, areaPath, featureTitle);
    }

    // -- deterministic ---------------------------------------------------------------------------

    private StoryDraft assemble(String storyMarkdown, WorkItemRef item, String knownChange, String parent, GrillHandoff grill, String areaPath, String featureTitle) {
        String story = appendOutOfScope(storyMarkdown, grill);
        if (StoryParser.title(story) == null && featureTitle != null && !featureTitle.isBlank()) {
            // draft/revise's prompt asks for a "# <title>" first line but LLM compliance varies;
            // inject the caller-computed fallback title deterministically so the review UI always
            // has a real H1 and extractChange (below) never falls back to its generic "change"
            // slug - mirrors the areaPath injection a few lines down.
            story = injectTitle(story, featureTitle);
        }
        String change = knownChange != null ? knownChange : extractChange(story);
        String area = StoryParser.area(story);
        if (area == null && areaPath != null && !areaPath.isBlank()) {
            // The draft/revise prompts don't reliably emit the "Feature: ... Area: X" marker
            // StoryParser.area() looks for (LLM output varies run to run); inject it deterministically
            // from the feature's board areaPath so plan-agent area resolution never depends on LLM luck.
            story = injectArea(story, areaPath);
            area = areaPath;
        }
        String slug = slugOf(change);

        Map<String, String> specDeltaFiles = buildSpecDelta(story, area);
        Map<String, String> invest = InvestValidator.validate(story);
        List<String> dorUnmet = DorValidator.validate(story, grill, specDeltaFiles);

        List<String> scenarios = StoryParser.scenarios(story).stream().map(StoryParser.Scenario::name).toList();
        Map<String, Object> nfr = extractNfr(story);
        List<String> areas = area == null ? List.of() : List.of(area);

        Handoff envelope = new Handoff("po-agent", "plan-agent", item.boardId(), CanonicalState.AWAITING_G1,
                List.of("board:" + item.profile() + ":" + item.boardId(), "repo:" + slug), 0.8, List.of(), List.of());
        PoHandoff handoff = new PoHandoff(envelope, parent, change, scenarios, nfr, areas, invest, dorUnmet, Map.of());
        return new StoryDraft(handoff, story, specDeltaFiles);
    }

    /** Prepends a {@code # <title>} heading, matching {@link StoryParser#title}'s single-{@code #}
     * prefix, when the LLM output has none of its own. */
    static String injectTitle(String storyMarkdown, String title) {
        return "# " + title + "\n\n" + storyMarkdown;
    }

    /** Inserts a plain {@code Area: <areaPath>} line right after the first {@code ## } heading (or
     * at the top if none), matching {@link StoryParser#area}'s {@code Area:\s*([^\s·]+)} regex -
     * deliberately plain text, not bold/markdown, so the regex's {@code \s*} matches. */
    static String injectArea(String storyMarkdown, String areaPath) {
        List<String> lines = new ArrayList<>(StoryParser.lines(storyMarkdown));
        int insertAt = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("## ")) {
                insertAt = i + 1;
                break;
            }
        }
        lines.add(insertAt, "Area: " + areaPath);
        return String.join("\n", lines);
    }

    /** Every parked question becomes an "Out of scope" line, if not already covered (playbook §2 step 2). */
    static String appendOutOfScope(String storyMarkdown, GrillHandoff grill) {
        if (grill == null || grill.parked().isEmpty()) {
            return storyMarkdown;
        }
        Map<String, GrillQuestion> byId = new LinkedHashMap<>();
        for (GrillQuestion q : grill.questions()) {
            byId.put(q.id(), q);
        }
        String existingOutOfScope = String.join("\n", StoryParser.sectionBullets(storyMarkdown, "Out of scope")).toLowerCase();
        List<String> additions = new ArrayList<>();
        for (String id : grill.parked()) {
            GrillQuestion q = byId.get(id);
            if (q == null) {
                continue;
            }
            if (existingOutOfScope.contains(id.toLowerCase()) || existingOutOfScope.contains(q.question().toLowerCase())) {
                continue;
            }
            additions.add("- " + q.question() + " (parked " + id + ")");
        }
        if (additions.isEmpty()) {
            return storyMarkdown;
        }
        StringBuilder sb = new StringBuilder(stripTrailingNewlines(storyMarkdown));
        if (!storyMarkdown.contains("## Out of scope")) {
            sb.append("\n\n## Out of scope\n");
        }
        for (String addition : additions) {
            sb.append('\n').append(addition);
        }
        return sb.append('\n').toString();
    }

    /** One ADDED requirement per scenario, GIVEN/WHEN/THEN copied verbatim from the story (playbook §2). */
    static Map<String, String> buildSpecDelta(String storyMarkdown, String area) {
        String areaName = area == null ? "default" : area;
        StringBuilder spec = new StringBuilder("# ").append(areaName).append("\n\n## ADDED Requirements\n");
        List<StoryParser.Scenario> scenarios = StoryParser.scenarios(storyMarkdown);
        for (StoryParser.Scenario s : scenarios) {
            spec.append("\n### Requirement: ").append(s.name()).append('\n');
            spec.append("The system SHALL support the \"").append(s.name()).append("\" scenario.\n");
            spec.append("\n#### Scenario: ").append(s.name()).append('\n');
            for (String g : s.given()) {
                spec.append("- **GIVEN** ").append(g).append('\n');
            }
            for (String w : s.when()) {
                spec.append("- **WHEN** ").append(w).append('\n');
            }
            for (String t : s.then()) {
                spec.append("- **THEN** ").append(t).append('\n');
            }
        }
        return Map.of("specs/" + areaName + "/spec.md", spec.toString());
    }

    static Map<String, Object> extractNfr(String storyMarkdown) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : StoryParser.sectionBullets(storyMarkdown, "NFR")) {
            Matcher m = NFR_BULLET.matcher(line);
            if (m.matches()) {
                out.put(m.group(1).trim(), m.group(2).trim());
            } else {
                out.put(line, "true");
            }
        }
        return out;
    }

    static String extractChange(String storyMarkdown) {
        Matcher m = CHANGE.matcher(storyMarkdown);
        if (m.find()) {
            return m.group(1);
        }
        String title = StoryParser.title(storyMarkdown);
        return "openspec/changes/" + slugify(title == null ? "change" : title);
    }

    private static String slugOf(String change) {
        String path = change == null ? "" : change;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    static String slugify(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String stripTrailingNewlines(String s) {
        String out = s;
        while (out.endsWith("\n")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static Map<String, Object> grillView(GrillHandoff grill) {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (GrillQuestion q : grill.questions()) {
            if (q.status() == GrillQuestion.Status.ANSWERED) {
                answers.add(Map.of("id", q.id(), "category", q.category().wireValue(), "answer", q.answer()));
            }
        }
        return Map.of("answers", answers, "parked", String.join(", ", grill.parked()));
    }

    /** Parses a PO agent follow-up reply: {@code null} when {@code output} doesn't start with
     * {@link #QUESTIONS_SENTINEL} (falls back to the story path) or on JSON parse failure; ids are
     * assigned sequentially from {@code firstIndex} and any {@code id} the LLM emits is ignored.
     * Unknown categories are skipped (mirrors {@code GrillAgent.mapQuestions}). */
    static List<GrillQuestion> parseFollowUps(String output, int firstIndex) {
        String s = output == null ? "" : output.strip();
        if (!s.startsWith(QUESTIONS_SENTINEL)) {
            return null;
        }
        String json = stripCodeFence(s.substring(QUESTIONS_SENTINEL.length()).strip());
        List<FollowUpDto> dtos;
        try {
            dtos = FOLLOW_UP_JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<FollowUpDto>>() {
            });
        } catch (Exception e) {
            log.warn("[po] follow-up JSON parse failed; falling back to the story path: {}", e.toString());
            return null;
        }
        List<GrillQuestion> out = new ArrayList<>();
        for (int i = 0; i < dtos.size(); i++) {
            FollowUpDto dto = dtos.get(i);
            if (dto.question() == null || dto.question().isBlank()) {
                log.warn("[po] ignoring follow-up dto with a blank question");
                continue;
            }
            GrillQuestion.Category category;
            try {
                category = GrillQuestion.Category.valueOf(dto.category().trim().toUpperCase());
            } catch (IllegalArgumentException | NullPointerException e) {
                log.warn("[po] ignoring follow-up question with unknown category {}", dto.category());
                continue;
            }
            String evidence = dto.evidence() == null || dto.evidence().isBlank() ? GrillQuestion.ASSUMPTION_CHECK : dto.evidence();
            out.add(new GrillQuestion(GrillQuestion.PO_ID_PREFIX + (firstIndex + i), category, dto.question(), evidence,
                    GrillQuestion.Status.OPEN, null, null));
        }
        return out;
    }

    /** Strips a leading/trailing ``` or ```json Markdown code fence some LLMs wrap the JSON array
     * in despite the prompt asking for raw JSON; a no-op when no fence is present. */
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

    /** Next {@code po*} id to assign — one past the highest {@code po*} number already asked, not a
     * count: a round may skip an unknown-category dto (leaving a gap — see {@link #parseFollowUps}),
     * so counting instead of taking the max would re-assign an id a later round already used. */
    static int nextPoIndex(GrillHandoff grill) {
        if (grill == null) {
            return 1;
        }
        int max = 0;
        for (GrillQuestion q : grill.questions()) {
            if (!q.askedByPoAgent()) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(q.id().substring(GrillQuestion.PO_ID_PREFIX.length())));
            } catch (NumberFormatException nonNumeric) {
                // Unexpected non-numeric po-prefixed id; ignore it rather than fail id assignment.
            }
        }
        return max + 1;
    }

    private com.embabel.agent.api.common.PromptRunner promptRunner() {
        if (poModel == null || poModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(poModel));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
