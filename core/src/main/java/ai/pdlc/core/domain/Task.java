package ai.pdlc.core.domain;

import java.time.Duration;
import java.util.List;

/**
 * One task the plan agent breaks a story into — {@code docs/tech-stack-architecture.md} §7
 * build-order step 3. Executed by the build worker (omp over ACP) on the branch named after the
 * parent story; {@code touches} is the scope contract the build loop must stay inside.
 *
 * @param id        task id within the plan, e.g. {@code T1}
 * @param title     short imperative title
 * @param area      code area (matches a {@link PoHandoff} area)
 * @param scenario  the spec-delta scenario this task proves
 * @param touches   files the build loop may edit; anything else is a scope violation
 * @param testPath  the test file the verifier runs to prove {@code scenario}
 * @param budget    iteration/token/wall-clock budget for the build loop
 * @param blockedBy task ids that must complete first (same-file conflicts, dependency order)
 */
public record Task(
        String id,
        String title,
        String area,
        String scenario,
        List<String> touches,
        String testPath,
        TaskBudget budget,
        List<String> blockedBy) {

    public Task {
        touches = touches == null ? List.of() : List.copyOf(touches);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    public record TaskBudget(int maxIterations, long maxTokens, Duration maxWallClock) {
    }
}
