package ai.pdlc.agents.po;

import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic Definition-of-Ready validation (playbook §2 "Validates — Definition of Ready").
 * Pilot subset: (1) every answered grill question is referenced in the story; (2) every parked
 * question appears as an "Out of scope" line; (3) the spec delta has at least one
 * ADDED/MODIFIED/REMOVED section per scenario. Returns the list of unmet checks; empty means ready.
 */
public final class DorValidator {

    private static final Pattern WORD = Pattern.compile("[a-z0-9]{4,}");
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "this", "that", "these", "those", "with", "from", "have", "will", "each", "every",
            "they", "them", "their", "there", "which", "would", "could", "should", "about", "into",
            "over", "under", "your", "when", "what", "where", "then", "than"));

    private DorValidator() {
    }

    public static List<String> validate(String storyMarkdown, GrillHandoff grill, Map<String, String> specDeltaFiles) {
        List<String> unmet = new ArrayList<>();
        if (grill == null) {
            return unmet;
        }
        String story = storyMarkdown == null ? "" : storyMarkdown.toLowerCase();
        List<String> outOfScope = StoryParser.sectionBullets(storyMarkdown, "Out of scope");
        String outOfScopeJoined = String.join(" ", outOfScope).toLowerCase();

        Map<String, GrillQuestion> byId = new java.util.HashMap<>();
        for (GrillQuestion q : grill.questions()) {
            byId.put(q.id(), q);
        }

        // (1) every answered question is referenced somewhere in the story.
        for (GrillQuestion q : grill.questions()) {
            if (q.status() != GrillQuestion.Status.ANSWERED) {
                continue;
            }
            if (!referenced(q, story)) {
                unmet.add("grill question " + q.id() + " answer not referenced in story");
            }
        }

        // (2) parked questions appear as "Out of scope" lines.
        for (String parkedId : grill.parked()) {
            GrillQuestion q = byId.get(parkedId);
            if (q == null) {
                continue;
            }
            if (!outOfScopeJoined.contains(q.id().toLowerCase()) && !tokensOverlap(q.question(), outOfScopeJoined)) {
                unmet.add("parked question " + q.id() + " has no out-of-scope line");
            }
        }

        // (3) spec delta has an ADDED/MODIFIED/REMOVED section per scenario.
        List<StoryParser.Scenario> scenarios = StoryParser.scenarios(storyMarkdown);
        String delta = specDeltaFiles == null ? "" : String.join("\n", specDeltaFiles.values()).toLowerCase();
        boolean hasDeltaSection = delta.contains("added") || delta.contains("modified") || delta.contains("removed");
        if (scenarios.isEmpty()) {
            unmet.add("story has no scenarios");
        } else if (!hasDeltaSection) {
            unmet.add("spec delta has no ADDED/MODIFIED/REMOVED section");
        } else {
            for (StoryParser.Scenario s : scenarios) {
                if (!delta.contains(s.name().toLowerCase())) {
                    unmet.add("spec delta has no section for scenario \"" + s.name() + "\"");
                }
            }
        }
        return unmet;
    }

    private static boolean referenced(GrillQuestion q, String story) {
        if (q.answer() == null || q.answer().isBlank()) {
            return true; // nothing to reference
        }
        if (tokensOverlap(q.answer(), story)) {
            return true;
        }
        // Fall back: the evidence source (e.g. a spec file) referenced by name.
        return q.evidence() != null && !q.evidence().isBlank() && tokensOverlap(q.evidence(), story);
    }

    private static boolean tokensOverlap(String source, String target) {
        for (String token : significantTokens(source)) {
            if (target.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> significantTokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        java.util.regex.Matcher m = WORD.matcher(text.toLowerCase());
        while (m.find()) {
            String token = m.group();
            if (!STOPWORDS.contains(token)) {
                out.add(token);
            }
        }
        return out;
    }
}
