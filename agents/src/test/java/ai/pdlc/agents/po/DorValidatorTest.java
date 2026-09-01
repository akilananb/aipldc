package ai.pdlc.agents.po;

import ai.pdlc.agents.fixtures.DemoFixtures;
import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.Handoff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DorValidatorTest {

    @Test
    void resolvedGrillPassesDoR() {
        Map<String, String> specDelta = PoAgent.buildSpecDelta(DemoFixtures.story(), "orders");
        List<String> unmet = DorValidator.validate(DemoFixtures.story(), DemoFixtures.grill(), specDelta);
        assertThat(unmet).isEmpty();
    }

    @Test
    void answeredQuestionNotReferencedIsUnmet() {
        GrillHandoff grill = new GrillHandoff(
                new Handoff("grill-agent", "po-agent", "4412", CanonicalState.READY_FOR_STORY,
                        List.of(), 0.8, List.of(), List.of()),
                "story",
                List.of(new GrillQuestion("q1", GrillQuestion.Category.SCOPE,
                        "scope?", "evidence-x", GrillQuestion.Status.ANSWERED,
                        "quantum cryptovault zephyr", "PO")),
                List.of(), List.of());
        Map<String, String> specDelta = PoAgent.buildSpecDelta(DemoFixtures.story(), "orders");
        List<String> unmet = DorValidator.validate(DemoFixtures.story(), grill, specDelta);
        assertThat(unmet).anyMatch(u -> u.contains("not referenced"));
    }

    @Test
    void parkedQuestionNeedsOutOfScopeLine() {
        GrillHandoff grill = new GrillHandoff(
                new Handoff("grill-agent", "po-agent", "4412", CanonicalState.READY_FOR_STORY,
                        List.of(), 0.8, List.of(), List.of()),
                "story",
                List.of(new GrillQuestion("q7", GrillQuestion.Category.NFR,
                        "Should we support PDF rendering too?", "assumption-check",
                        GrillQuestion.Status.PARKED, null, null)),
                List.of("q7"), List.of());
        // Story has no out-of-scope line covering this parked question (id or its subject matter).
        String story = DemoFixtures.story().replace("- scheduled exports (parked q7)", "- scheduled exports");
        Map<String, String> specDelta = PoAgent.buildSpecDelta(story, "orders");
        List<String> unmet = DorValidator.validate(story, grill, specDelta);
        assertThat(unmet).anyMatch(u -> u.contains("out-of-scope"));
    }

    @Test
    void specDeltaMustCoverEveryScenario() {
        Map<String, String> incompleteDelta = Map.of("specs/orders/spec.md", "# orders\n\n## ADDED Requirements\n");
        List<String> unmet = DorValidator.validate(DemoFixtures.story(), DemoFixtures.grill(), incompleteDelta);
        assertThat(unmet).anyMatch(u -> u.contains("no section for scenario"));
    }

    @Test
    void appendedOutOfScopeLinesResolveParked() {
        // PoAgent.appendOutOfScope turns parked questions into out-of-scope lines, satisfying DoR (2).
        String appended = PoAgent.appendOutOfScope(DemoFixtures.story(), DemoFixtures.grill());
        assertThat(appended).contains("(parked q2)").contains("(parked q5)");
        Map<String, String> specDelta = PoAgent.buildSpecDelta(appended, "orders");
        List<String> unmet = DorValidator.validate(appended, DemoFixtures.grill(), specDelta);
        assertThat(unmet).isEmpty();
    }
}
