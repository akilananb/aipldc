package ai.pdlc.agents.po;

import ai.pdlc.agents.fixtures.DemoFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvestValidatorTest {

    @Test
    void vagueThenFailsT() {
        String story = """
                # Export orders
                Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders
                As a sales admin
                I want to export orders
                So that I can share data

                ## Acceptance criteria
                Scenario: export
                  GIVEN the user has filtered the grid
                  WHEN  the user clicks Export
                  THEN  the export works well
                """;
        assertThat(InvestValidator.testable(story)).isFalse();
        assertThat(InvestValidator.validate(story).get("T")).isEqualTo("fail");
    }

    @Test
    void observableThenPassesT() {
        String story = """
                # Export orders
                Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders
                As a sales admin
                I want to export orders
                So that I can share data

                ## Acceptance criteria
                Scenario: export
                  GIVEN the user has filtered the grid to 500 rows
                  WHEN  the user clicks Export CSV
                  THEN  a CSV file downloads with exactly 500 rows
                """;
        assertThat(InvestValidator.testable(story)).isTrue();
        assertThat(InvestValidator.validate(story).get("T")).isEqualTo("pass");
    }

    @Test
    void demoStoryPassesAllSixLetters() {
        Map<String, String> result = InvestValidator.validate(DemoFixtures.story());
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                "I", "pass", "N", "pass", "V", "pass", "E", "pass", "S", "pass", "T", "pass"));
    }

    @Test
    void implementationNounFailsN() {
        String story = """
                # Export orders
                Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders
                As a sales admin
                I want to export orders
                So that I can share data

                ## Acceptance criteria
                Scenario: export
                  GIVEN the user has filtered the grid
                  WHEN  the user clicks Export CSV
                  THEN  a CSV file downloads

                ## Dependencies
                - use the CsvExporter class
                """;
        assertThat(InvestValidator.negotiable(story)).isFalse();
    }

    @Test
    void openStoryDependencyFailsI() {
        String story = """
                # Export orders
                Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders
                As a sales admin
                I want to export orders
                So that I can share data

                ## Acceptance criteria
                Scenario: export
                  GIVEN the user has filtered the grid
                  WHEN  the user clicks Export CSV
                  THEN  a CSV file downloads

                ## Dependencies
                - blocked on feature #4413
                """;
        assertThat(InvestValidator.independent(story)).isFalse();
    }
}
