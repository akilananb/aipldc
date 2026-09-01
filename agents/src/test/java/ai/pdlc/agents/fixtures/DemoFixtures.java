package ai.pdlc.agents.fixtures;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.GrillHandoff;
import ai.pdlc.core.domain.GrillQuestion;
import ai.pdlc.core.domain.Handoff;

import java.util.List;

/**
 * Shared fixtures for the deterministic agent tests and (kept in sync with) the stub-llm mappings.
 * The {@code story()} layout is load-bearing: line 13 is the rate-limit {@code GIVEN} line that the
 * e2e demo's {@code line:13} blocking comment targets and the {@code [agent:po-revise]} revision
 * changes to "20 for admin".
 */
public final class DemoFixtures {

    private DemoFixtures() {
    }

    public static String story() {
        return """
                # Export the filtered orders view to CSV
                Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders

                As a sales admin
                I want to export the current filtered orders view to CSV
                So that I can share order data with external stakeholders
                ## Acceptance criteria
                Scenario: export-current-view
                  GIVEN the sales admin has filtered the orders grid to 500 rows
                  WHEN  the user clicks Export CSV
                  THEN  a CSV file downloads with exactly 500 rows
                Scenario: rate-limit
                  GIVEN 10 exports in the last hour
                  WHEN  the user requests an 11th export
                  THEN  the request returns HTTP 429 with Retry-After
                Scenario: audit
                  GIVEN the sales admin exports the filtered view
                  WHEN  the export completes
                  THEN  an audit row is written with user, filter hash, and row count

                ## NFR
                - performance: 10k rows < 5 s (p95); progress indicator after 2 s
                - security: sales, admin roles only; every export audited (user, filter hash, row count)
                - limits: 10 exports / user / hour → 429 with Retry-After

                ## Out of scope
                - scheduled exports (parked q7)
                - XLSX format

                ## Dependencies
                - streaming endpoint in orders-service (same story, task T1)
                - none external

                ## Open decisions for approvers
                - none
                """.stripTrailing();
    }

    public static String revisedStory() {
        return """
                # Export the filtered orders view to CSV
                Feature: #4412 · Change: openspec/changes/export-orders-csv · Area: orders

                As a sales admin
                I want to export the current filtered orders view to CSV
                So that I can share order data with external stakeholders
                ## Acceptance criteria
                Scenario: export-current-view
                  GIVEN the sales admin has filtered the orders grid to 500 rows
                  WHEN  the user clicks Export CSV
                  THEN  a CSV file downloads with exactly 500 rows
                Scenario: rate-limit
                  GIVEN 20 for admin exports in the last hour
                  WHEN  the user requests an 11th export
                  THEN  the request returns HTTP 429 with Retry-After
                Scenario: audit
                  GIVEN the sales admin exports the filtered view
                  WHEN  the export completes
                  THEN  an audit row is written with user, filter hash, and row count

                ## NFR
                - performance: 10k rows < 5 s (p95); progress indicator after 2 s
                - security: sales, admin roles only; every export audited (user, filter hash, row count)
                - limits: 20 for admin / user / hour → 429 with Retry-After

                ## Out of scope
                - scheduled exports (parked q7)
                - XLSX format

                ## Dependencies
                - streaming endpoint in orders-service (same story, task T1)
                - none external

                ## Open decisions for approvers
                - none
                """.stripTrailing();
    }

    /** Resolved grill handoff for the demo: q1/q4 answered, the rest parked. */
    public static GrillHandoff grill() {
        return new GrillHandoff(
                new Handoff("grill-agent", "po-agent", "4412", CanonicalState.READY_FOR_STORY,
                        List.of("board:local:4412"), 0.8, List.of(), List.of()),
                "story",
                List.of(
                        answered("q1", GrillQuestion.Category.SCOPE,
                                "Which orders — everything the user can see, or the current filtered view?",
                                "orders-grid uses server-side paging (src/orders/grid.tsx:41)",
                                "Current filtered view, max 10k rows.", "PO"),
                        parked("q2", GrillQuestion.Category.USERS,
                                "Who can export — all roles or sales/admin only?",
                                "assumption-check"),
                        parked("q3", GrillQuestion.Category.ACCEPTANCE,
                                "What file format and columns must the export contain?",
                                "assumption-check"),
                        answered("q4", GrillQuestion.Category.RISK,
                                "Orders contain customer PII. Log and rate-limit exports?",
                                "docs/constraints.md#data-classification; INC-2210",
                                "Audit every export; 10 per user per hour.", "PO"),
                        parked("q5", GrillQuestion.Category.DEPENDENCY,
                                "Does the export reuse the existing streaming endpoint?",
                                "assumption-check"),
                        parked("q6", GrillQuestion.Category.NFR,
                                "What is the export volume and latency ceiling?",
                                "assumption-check")),
                List.of("q2", "q3", "q5", "q6"),
                List.of("pii", "rate-limit"));
    }

    private static GrillQuestion answered(String id, GrillQuestion.Category category, String question,
                                          String evidence, String answer, String by) {
        return new GrillQuestion(id, category, question, evidence, GrillQuestion.Status.ANSWERED, answer, by);
    }

    private static GrillQuestion parked(String id, GrillQuestion.Category category, String question, String evidence) {
        return new GrillQuestion(id, category, question, evidence, GrillQuestion.Status.PARKED, null, null);
    }
}
