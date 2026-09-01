package ai.pdlc.controlplane.review;

import ai.pdlc.controlplane.persistence.CommentEntity;
import ai.pdlc.core.domain.Anchor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-anchors comments onto a (possibly new) rendering of the story markdown - tech-stack §3.2: match
 * by {@code anchorText} hash first, then by {@code scenario} name, then fall back to the original
 * line number and mark the comment {@code drifted} for the checker to confirm.
 */
@Service
public class CommentReanchorer {

    private static final Pattern SCENARIO_HEADING = Pattern.compile("^\\s*Scenario:\\s*(.+)$");
    private final ObjectMapper mapper = new ObjectMapper();

    public record Line(int number, String text, String hash, String scenario) {
    }

    public record Anchored(CommentEntity comment, Anchor anchor, boolean drifted) {
    }

    /** Splits markdown into lines, computing each line's anchor hash and enclosing scenario. */
    public List<Line> index(String markdown) {
        List<Line> lines = new ArrayList<>();
        String currentScenario = null;
        int lineNo = 0;
        for (String raw : markdown.lines().toList()) {
            lineNo++;
            Matcher m = SCENARIO_HEADING.matcher(raw);
            if (m.matches()) {
                currentScenario = m.group(1).trim();
            }
            lines.add(new Line(lineNo, raw, Anchor.hash(raw), currentScenario));
        }
        return lines;
    }

    public Anchored reanchor(CommentEntity comment, List<Line> lines) {
        Anchor original = parseAnchor(comment.anchorJson());
        if (original == null) {
            return new Anchored(comment, null, false);
        }

        Optional<Line> byHash = lines.stream().filter(l -> l.hash().equals(original.anchorText())).findFirst();
        if (byHash.isPresent()) {
            Line l = byHash.get();
            return new Anchored(comment, new Anchor(l.number(), l.hash(), original.nodeType(), l.scenario()), false);
        }

        if (original.scenario() != null) {
            Optional<Line> byScenario = lines.stream()
                    .filter(l -> original.scenario().equals(l.scenario()))
                    .findFirst();
            if (byScenario.isPresent()) {
                Line l = byScenario.get();
                return new Anchored(comment, new Anchor(l.number(), l.hash(), original.nodeType(), l.scenario()), false);
            }
        }

        return new Anchored(comment, original, true); // fallback: same line, drifted
    }

    public String toAnchorJson(Anchor anchor) {
        try {
            return mapper.writeValueAsString(anchor);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private Anchor parseAnchor(String anchorJson) {
        if (anchorJson == null || anchorJson.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(anchorJson, Anchor.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }
}
