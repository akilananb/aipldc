package ai.pdlc.core.workflow;

import ai.pdlc.core.config.RepoConfig;
import ai.pdlc.core.domain.PlanConsultation;
import ai.pdlc.core.domain.PlanDecision;
import ai.pdlc.core.domain.PlanResult;
import ai.pdlc.core.domain.PoHandoff;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.plan.PlanAssembler;
import io.temporal.failure.ApplicationFailure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Deterministic control flow for the Plan Agent's bounded repository consultation — task-breakdown
 * ownership moves from a one-shot deterministic planner to a reasoning Plan Agent that consults the
 * repository through the build-worker's ACP-driven consultant, zero or more bounded rounds, before
 * ever proposing tasks. Called once per story, right after G1 approval, in place of the old
 * {@code planner.planTasks} activity call — see {@link FeatureWorkflowImpl#run} step 6.
 *
 * <p>Bounded: at most {@link #MAX_CONSULTATIONS} successful repository consultations and at most
 * {@link #MAX_DECISIONS} reasoning decisions total. The first decision must be CONSULT — a task
 * breakdown drafted with zero repository evidence is never accepted; every FINALIZE validation
 * failure feeds the rejection reason back as {@code feedback} to the next decision without
 * publishing anything. BLOCKED, decision exhaustion, or final invalidity with no decisions left
 * throws a non-retryable {@link ApplicationFailure} (category {@code planning-blocked}) — the
 * story stays {@code approved}; no child tasks or builds are ever created. An activity failure
 * from either {@link AgentActivities#planNextStep} or {@link BuildActivities#consultPlan} is routed
 * through the caller-supplied {@code reasoningRunner}/{@code consultRunner} ({@link
 * FeatureWorkflowImpl#reasoningStep} in production — blocks the workflow in place awaiting a
 * reviewer retry, exactly like every other bounded-retry LLM call) rather than being caught here;
 * {@link PlanningLoop} itself has no {@code Workflow.*} dependency and stays directly unit-testable
 * (a test passes a passthrough runner, preserving today's immediate-propagation behavior).
 */
final class PlanningLoop {

    static final int MAX_CONSULTATIONS = 3;
    static final int MAX_DECISIONS = 6;

    private PlanningLoop() {
    }

    /** Wraps one named reasoning/consultation step — production ({@link FeatureWorkflowImpl})
     * binds this to {@code reasoningStep}, blocking-and-retrying on a bounded-retry exhaustion;
     * tests bind a passthrough that just calls {@code call.get()} immediately. Generic at the
     * interface level (not the method level) so a lambda can implement it. */
    @FunctionalInterface
    interface StepRunner<T> {
        T run(String step, Supplier<T> call);
    }

    static PlanResult run(WorkItemRef story, PoHandoff po, String storyMarkdown, List<String> initialFeedback,
                           List<RepoConfig> repos, AgentActivities reasoning, BuildActivities consultant,
                           StepRunner<PlanDecision> reasoningRunner, StepRunner<PlanConsultation.Report> consultRunner) {
        List<String> repoIds = repos.stream().map(RepoConfig::id).toList();
        List<PlanConsultation.Exchange> history = new ArrayList<>();
        List<String> feedback = List.copyOf(initialFeedback);
        int decisions = 0;
        int consultations = 0;
        Map<String, String> pinnedByRepo = new LinkedHashMap<>();

        while (true) {
            if (decisions >= MAX_DECISIONS) {
                throw blocked("planning exhausted " + MAX_DECISIONS
                        + " reasoning decisions without a valid final plan");
            }
            boolean canConsult = consultations < MAX_CONSULTATIONS;
            List<String> feedbackForDecision = feedback;
            PlanDecision decision = reasoningRunner.run("plan-next-step", () -> reasoning.planNextStep(
                    story, po, storyMarkdown, repos, List.copyOf(history), feedbackForDecision, canConsult));
            decisions++;
            feedback = List.of();

            if (decisions == 1 && decision.action() != PlanDecision.Action.CONSULT
                    && decision.action() != PlanDecision.Action.BLOCKED) {
                feedback = List.of("the first planning decision must be CONSULT - a task breakdown "
                        + "with zero repository evidence is never accepted");
                continue;
            }

            switch (decision.action()) {
                case BLOCKED -> {
                    String reason = decision.reason() == null || decision.reason().isBlank()
                            ? "plan agent returned BLOCKED with no reason" : decision.reason();
                    throw blocked(reason);
                }
                case CONSULT -> {
                    if (!canConsult) {
                        feedback = List.of("the consultation budget (" + MAX_CONSULTATIONS
                                + ") is exhausted - finalize from existing evidence or return BLOCKED");
                        continue;
                    }
                    String repoId = decision.repo();
                    if (repoId == null || repoId.isBlank() || !repoIds.contains(repoId)) {
                        feedback = List.of("CONSULT must name one of the project repos: " + repoIds);
                        continue;
                    }
                    List<String> questions = decision.questions();
                    boolean questionsOk = !questions.isEmpty() && questions.size() <= 8
                            && questions.stream().noneMatch(q -> q == null || q.isBlank());
                    if (!questionsOk) {
                        feedback = List.of("CONSULT requires 1-8 nonblank questions");
                        continue;
                    }
                    PlanConsultation consultation = new PlanConsultation(
                            history.size() + 1, repoId, questions, decision.paths(),
                            pinnedByRepo.getOrDefault(repoId, ""), List.copyOf(history));
                    PlanConsultation.Report report = consultRunner.run("plan-consult",
                            () -> consultant.consultPlan(story, po, consultation));
                    validateReportConsistency(report, pinnedByRepo.getOrDefault(repoId, ""));
                    pinnedByRepo.put(repoId, report.baseCommit());
                    history.add(new PlanConsultation.Exchange(consultation.round(), repoId, questions, report));
                    consultations++;
                }
                case FINALIZE -> {
                    if (history.isEmpty()) {
                        feedback = List.of("FINALIZE requires at least one successful consultation first");
                        continue;
                    }
                    try {
                        return PlanAssembler.assemble(story, po, decision.tasks(), List.copyOf(history), repos);
                    } catch (IllegalArgumentException invalid) {
                        if (decisions >= MAX_DECISIONS) {
                            throw blocked("final plan invalid and no reasoning decisions remain: "
                                    + invalid.getMessage());
                        }
                        feedback = List.of(invalid.getMessage());
                    }
                }
            }
        }
    }

    /** A consultant report must name the exact commit it checked out, and every report in one
     * transcript must agree on that snapshot — the same invariant {@link PlanAssembler} enforces
     * again at FINALIZE time as a defense-in-depth check across the whole history. */
    private static void validateReportConsistency(PlanConsultation.Report report, String pinnedBaseCommit) {
        if (report == null) {
            throw new IllegalStateException("consultant returned no report");
        }
        if (report.baseCommit() == null || report.baseCommit().isBlank()) {
            throw new IllegalStateException("consultant report is missing a baseCommit");
        }
        if (!pinnedBaseCommit.isBlank() && !pinnedBaseCommit.equals(report.baseCommit())) {
            throw new IllegalStateException("consultant report snapshot " + report.baseCommit()
                    + " does not match the pinned commit " + pinnedBaseCommit);
        }
    }

    private static ApplicationFailure blocked(String reason) {
        return ApplicationFailure.newNonRetryableFailure(reason, "planning-blocked");
    }
}
