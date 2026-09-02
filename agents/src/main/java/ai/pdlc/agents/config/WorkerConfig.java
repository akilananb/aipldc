package ai.pdlc.agents.config;

import ai.pdlc.agents.activities.AgentActivitiesImpl;
import ai.pdlc.core.workflow.AgentMentionWorkflowImpl;
import ai.pdlc.core.workflow.FeatureWorkflowImpl;
import ai.pdlc.core.workflow.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code agents} worker hosts the {@code AgentActivities} implementation AND (unlike
 * control-plane's worker) registers {@link FeatureWorkflowImpl} as the workflow implementation type,
 * both on task queue {@link TaskQueues#REASONING} (orchestration-decision §5 "Queues per role" —
 * pilot uses the single queue).
 */
@Configuration
public class WorkerConfig {

    private WorkerFactory factory;

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client, AgentActivitiesImpl agentActivities) {
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TaskQueues.REASONING);
        worker.registerWorkflowImplementationTypes(FeatureWorkflowImpl.class, AgentMentionWorkflowImpl.class);
        worker.registerActivitiesImplementations(agentActivities);
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
