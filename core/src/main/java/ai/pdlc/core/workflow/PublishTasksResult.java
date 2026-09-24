package ai.pdlc.core.workflow;

import java.util.Map;

/** Result of {@link BoardSideEffects#publishTasks}: each project repo id mapped to its default
 * branch (the target that repo's PR opens against) plus each {@link ai.pdlc.core.domain.Task#id()}
 * mapped to the board id of the task item just created — used to file each task's advisory
 * quality report. */
public record PublishTasksResult(Map<String, String> defaultBranchByRepo, Map<String, String> taskBoardIds) {
    public PublishTasksResult {
        defaultBranchByRepo = defaultBranchByRepo == null ? Map.of() : Map.copyOf(defaultBranchByRepo);
        taskBoardIds = taskBoardIds == null ? Map.of() : Map.copyOf(taskBoardIds);
    }
}
