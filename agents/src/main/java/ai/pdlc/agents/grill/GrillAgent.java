package ai.pdlc.agents.grill;

import ai.pdlc.agents.activities.AgentContext;
import ai.pdlc.agents.templates.PromptTemplates;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.workflow.BoardCommentEvent;
import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grill agent (playbook §1) as a plain Spring service. The typed-question generation runs through
 * Embabel's {@link Ai} (real API, {@code generateText}) with an {@code [agent:grill]} marker the
 * stub-llm matches on; everything else — the six fixed categories, the mandatory risk question, and
 * answer evaluation — is deterministic Java (the pre-decided plain-guard fallback for {@code @Condition}).
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

    private static final Pattern ANSWER_MARKER = Pattern.compile("(?i)\\b((?:q|po)\\d+)\\s*:");

    private static final String PARK_KEYWORD = "park";

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Wire shape of the stub/LLM grill response; snake_case via {@link #JSON}'s naming strategy. */
    public record GrillResponse(String typeDecision, List<QuestionDto> questions, List<String> constraintsHit) {
    }

    public record QuestionDto(String id, String category, String question, String evidence) {
    }

    private final Ai ai;
    private final BoardPort board;
    private final PromptTemplates templates;
    private final String grillModel;

    public GrillAgent(Ai ai, BoardPort board, PromptTemplates templates, Profile activeProfile) {
        this.ai = ai;
        this.board = board;
        this.templates = templates;
        var role = activeProfile.agents().roles().get("grill");
        this.grillModel = role != null ? role.model() : null;
    }

    public GrillHandoff evaluate(WorkItemRef item, GrillHandoff previous, List<BoardCommentEvent> newComments) {
        if (previous == null) {
            return generate(item);
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

    /** The mandatory risk question, citing the constraint hit. */
    public static GrillQuestion mandatoryRiskQuestion(String id) {
        return new GrillQuestion(id, GrillQuestion.Category.RISK,
                "Orders contain customer PII. Log and rate-limit exports?",
                "docs/constraints.md#data-classification", GrillQuestion.Status.OPEN, null, null);
    }

    /** Adds a mandatory RISK question iff the keyword hit and none is already present. */
    public static List<GrillQuestion> ensureRiskQuestion(List<GrillQuestion> questions, String title, String description) {
        boolean hasRisk = questions.stream().anyMatch(q -> q.category() == GrillQuestion.Category.RISK);
        if (hasRisk || !riskKeywordHit(title, description)) {
            return questions;
        }
        List<GrillQuestion> out = new ArrayList<>(questions);
        out.add(mandatoryRiskQuestion("q" + (questions.size() + 1)));
        return out;
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
     * unreferenced stays {@code open} — no auto-park (playbook §1, revised: humans control park). */
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
                updated.add(q.parked());
                newlyParked.add(q.id());
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

    private GrillHandoff generate(WorkItemRef item) {
        WorkItem workItem = AgentContext.readWorkItem(board, item);
        List<ai.pdlc.core.domain.Comment> boardComments = AgentContext.readComments(board, item);
        String title = workItem == null ? item.boardId() : safe(workItem.title());
        String description = workItem == null ? "" : safe(workItem.description());

        String prompt = templates.render("grill-questions", Map.of("title", title, "description", description));

        GrillResponse response;
        try {
            String raw = promptRunner().generateText(prompt);
            response = JSON.readValue(raw, GrillResponse.class);
        } catch (Exception e) {
            log.warn("[grill] LLM call or JSON parse failed; falling back to a single risk question: {}", e.toString());
            response = new GrillResponse("story", List.of(), List.of());
        }

        List<GrillQuestion> questions = mapQuestions(response.questions());
        questions = ensureRiskQuestion(questions, title, description);
        Handoff envelope = new Handoff("grill-agent", "po-agent", item.boardId(), CanonicalState.READY_FOR_STORY,
                List.of("board:" + item.profile() + ":" + item.boardId(), "board-comments:" + boardComments.size()),
                0.8, List.of(), List.of());
        return new GrillHandoff(envelope, response.typeDecision() == null ? "story" : response.typeDecision(),
                questions, List.of(), response.constraintsHit() == null ? List.of() : response.constraintsHit());
    }

    private List<GrillQuestion> mapQuestions(List<QuestionDto> dtos) {
        List<GrillQuestion> out = new ArrayList<>();
        if (dtos == null) {
            return out;
        }
        for (QuestionDto dto : dtos) {
            try {
                GrillQuestion.Category category = GrillQuestion.Category.valueOf(dto.category().trim().toUpperCase());
                out.add(new GrillQuestion(dto.id(), category, dto.question(), dto.evidence(),
                        GrillQuestion.Status.OPEN, null, null));
            } catch (IllegalArgumentException e) {
                log.warn("[grill] ignoring question {} with unknown category {}", dto.id(), dto.category());
            }
        }
        return out;
    }

    private com.embabel.agent.api.common.PromptRunner promptRunner() {
        if (grillModel == null || grillModel.isBlank()) {
            return ai.withDefaultLlm();
        }
        return ai.withLlm(LlmOptions.withModel(grillModel));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
