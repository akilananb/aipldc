package ai.pdlc.agents.po;

import ai.pdlc.agents.fixtures.DemoFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavior tests for the canonical story layout's parsing contract (playbook §2 Story format). */
class StoryParserTest {

    @Test
    void scenariosAttachesAndAfterWhenToWhenAndIgnoresKeywordLinesAfterATrailingHeading() {
        String story = """
                ## Acceptance Criteria
                Scenario: only
                  Given a precondition
                  When an action
                  And another action
                  Then a result

                ## Dependencies
                Then again, none.
                """;

        List<StoryParser.Scenario> scenarios = StoryParser.scenarios(story);

        assertThat(scenarios).hasSize(1);
        StoryParser.Scenario only = scenarios.get(0);
        assertThat(only.when()).containsExactly("an action", "another action");
        assertThat(only.then()).containsExactly("a result");
    }

    @Test
    void sectionBulletsReturnsOutOfScopeBulletsAndStopsAtAcceptanceCriteria() {
        String story = """
                ## Requirements
                ### Out of Scope
                - scheduled exports
                - XLSX format

                ## Acceptance Criteria
                Scenario: export
                  Given a precondition
                  When an action
                  Then a result
                """;

        assertThat(StoryParser.sectionBullets(story, "Out of Scope"))
                .containsExactly("scheduled exports", "XLSX format");
    }

    @Test
    void outcomeReturnsTheSoThatLineAndNullWhenAbsent() {
        assertThat(StoryParser.outcome(DemoFixtures.story()))
                .isEqualTo("I can share order data with external stakeholders");

        String noOutcome = """
                ## Story
                As a sales admin,
                I want to export the filtered view.
                """;
        assertThat(StoryParser.outcome(noOutcome)).isNull();

        String legacy = """
                ## Story
                When x,
                Then y,
                That they export it.
                """;
        assertThat(StoryParser.outcome(legacy)).isEqualTo("they export it");
    }
}
