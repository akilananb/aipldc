package ai.pdlc.core.domain;

import java.util.List;

/**
 * Plan agent → build loop handoff — {@code docs/tech-stack-architecture.md} §7 build-order step 3.
 * Extends the base {@link Handoff} envelope by composition (Java records cannot extend records).
 *
 * @param envelope base handoff envelope
 * @param tasks    every task, in no particular order
 * @param waves    task ids grouped into sequential waves; tasks within one wave run in parallel,
 *                 a wave only starts once every earlier wave's tasks are green
 */
public record PlanHandoff(Handoff envelope, List<Task> tasks, List<List<String>> waves) {

    public PlanHandoff {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        waves = waves == null ? List.of() : List.copyOf(waves);
    }

    public Task task(String id) {
        return tasks.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such task: " + id));
    }
}
