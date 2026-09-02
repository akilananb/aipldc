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

    public List<StoryDraft> draft(WorkItemRef item, GrillHandoff grill) {
        WorkItem workItem = AgentContext.readWorkItem(board, item);
        String title = workItem == null ? item.boardId() : safe(workItem.title());
        String description = workItem == null ? "" : safe(workItem.description());

        Map<String, Object> view = new HashMap<>();
        view.put("title", title);
        view.put("description", description);
        if (grill != null) {
            view.put("grill", grillView(grill));
        }
        String prompt = templates.render("po-draft", view);

        String output = promptRunner().generateText(prompt);
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
        for (String part : parts) {
            StoryDraft draft = assemble(part, item, null, item.boardId(), grill);
            if (!seenChanges.add(draft.handoff().change())) {
                draft = assemble(part, item, draft.handoff().change() + "-s" + (drafts.size() + 1), item.boardId(), grill);
                seenChanges.add(draft.handoff().change());
            }
            drafts.add(draft);
        }
        return drafts;
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
        return assemble(revised, item, previous.change(), previous.parent(), null);
    }

    // -- deterministic ---------------------------------------------------------------------------

    private StoryDraft assemble(String storyMarkdown, WorkItemRef item, String knownChange, String parent, GrillHandoff grill) {
        String story = appendOutOfScope(storyMarkdown, grill);
        String change = knownChange != null ? knownChange : extractChange(story);
        String area = StoryParser.area(story);
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
