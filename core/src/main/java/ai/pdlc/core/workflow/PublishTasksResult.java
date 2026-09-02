package ai.pdlc.core.workflow;

import java.util.Map;

/** Result of {@link BoardSideEffects#publishTasks}: the repo's default branch (the target the
 * story's PR opens against) plus each {@link ai.pdlc.core.domain.Task#id()} mapped to the board id
 * of the task item just created — used to file each task's advisory quality report. */
public record PublishTasksResult(String defaultBranch, Map<String, String> taskBoardIds) {
    public PublishTasksResult {
        taskBoardIds = taskBoardIds == null ? Map.of() : Map.copyOf(taskBoardIds);
    }
}
