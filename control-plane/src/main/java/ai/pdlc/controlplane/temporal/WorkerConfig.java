package ai.pdlc.controlplane.temporal;

import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hosts {@link BoardSideEffectsImpl} on task queue {@link TaskQueues#BOARD} - its own dedicated
 * queue, not shared with agents' worker (see {@link TaskQueues}'s javadoc for the dispatch-mismatch
 * bug this fixes). Does NOT register {@code FeatureWorkflow} — per plan step 5, "control-plane
 * starts it; worker lives in agents".
 */
@Configuration
public class WorkerConfig {

    private WorkerFactory factory;

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client, BoardSideEffectsImpl boardSideEffects,
                                        BuildActivitiesImpl buildActivities) {
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TaskQueues.BOARD);
        worker.registerActivitiesImplementations(boardSideEffects);
        factory.newWorker(TaskQueues.BUILD).registerActivitiesImplementations(buildActivities);
        factory.start();
        return factory;
    }

    @PreDestroy
    public void shutdown() {
        if (factory != null) {
            factory.shutdown();
        }
    }
}
